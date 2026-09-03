package com.cicd.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.ResourceUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GitHubAppService {

    private static final Logger log = LoggerFactory.getLogger(GitHubAppService.class);

    private final WebClient webClient;

    @Value("${github.app.id}")
    private String appId;

    @Value("${github.app.private-key-path}")
    private String privateKeyPath;

    private PrivateKey privateKey;

    // Cache installation tokens: installationId -> { token, expiresAt }
    private final Map<Long, InstallationToken> tokenCache = new ConcurrentHashMap<>();

    public GitHubAppService() {
        this.webClient = WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    private PrivateKey getPrivateKey() {
        if (privateKey != null) {
            return privateKey;
        }
        try {
            File file = ResourceUtils.getFile(privateKeyPath);
            try (PEMParser pemParser = new PEMParser(new FileReader(file))) {
                Object object = pemParser.readObject();
                JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
                if (object instanceof PrivateKeyInfo) {
                    privateKey = converter.getPrivateKey((PrivateKeyInfo) object);
                } else if (object instanceof org.bouncycastle.openssl.PEMKeyPair) {
                    privateKey = converter.getPrivateKey(((org.bouncycastle.openssl.PEMKeyPair) object).getPrivateKeyInfo());
                } else {
                    throw new IllegalArgumentException("Unknown PEM object: " + object.getClass().getName());
                }
            }
            return privateKey;
        } catch (Exception e) {
            log.error("Failed to load GitHub App private key", e);
            throw new RuntimeException("Could not load private key", e);
        }
    }

    public String generateAppJwt() {
        long nowMillis = System.currentTimeMillis();
        long expMillis = nowMillis + (10 * 60 * 1000); // 10 minutes maximum

        return Jwts.builder()
                .setIssuer(appId)
                .setIssuedAt(new Date(nowMillis))
                .setExpiration(new Date(expMillis))
                .signWith(getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();
    }

    public String getInstallationToken(Long installationId) {
        if (installationId == null) {
            throw new IllegalArgumentException("Installation ID cannot be null");
        }

        InstallationToken cachedToken = tokenCache.get(installationId);
        // Add 1 minute buffer for expiration
        if (cachedToken != null && cachedToken.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return cachedToken.token();
        }

        log.info("Requesting new installation token for installation {}", installationId);
        String jwt = generateAppJwt();

        JsonNode response = webClient.post()
                .uri("/app/installations/{installation_id}/access_tokens", installationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (response != null && response.has("token")) {
            String token = response.get("token").asText();
            Instant expiresAt = Instant.parse(response.get("expires_at").asText());
            tokenCache.put(installationId, new InstallationToken(token, expiresAt));
            return token;
        }

        throw new RuntimeException("Failed to obtain installation access token");
    }

    private record InstallationToken(String token, Instant expiresAt) {}
}

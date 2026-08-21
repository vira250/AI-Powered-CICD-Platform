package com.aicicd.platform.github;

import com.aicicd.platform.config.AppProperties;
import io.jsonwebtoken.Jwts;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.FileReader;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GitHub App authentication:
 *  1. Signs a short-lived App JWT (RS256) with the App's private key.
 *  2. Exchanges it for per-installation access tokens (cached until expiry).
 */
@Service
public class GitHubAppService {

    private final AppProperties props;
    private final RestClient http;
    private PrivateKey privateKey;

    private record CachedToken(String token, Instant expiresAt) {}
    private final Map<Long, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public GitHubAppService(AppProperties props) {
        this.props = props;
        this.http = RestClient.builder()
                .baseUrl(props.github().apiBase())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** RS256 App JWT, valid for 9 minutes. */
    public String createAppJwt() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(props.github().appId())
                .issuedAt(Date.from(now.minus(30, ChronoUnit.SECONDS)))
                .expiration(Date.from(now.plus(9, ChronoUnit.MINUTES)))
                .signWith(loadPrivateKey(), Jwts.SIG.RS256)
                .compact();
    }

    private synchronized PrivateKey loadPrivateKey() {
        if (privateKey != null) return privateKey;
        try (PEMParser parser = new PEMParser(new FileReader(props.github().privateKeyPath()))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (obj instanceof PrivateKeyInfo info) {              // PKCS#8
                privateKey = converter.getPrivateKey(info);
            } else if (obj instanceof org.bouncycastle.openssl.PEMKeyPair pair) { // PKCS#1
                privateKey = converter.getPrivateKey(pair.getPrivateKeyInfo());
            } else {
                throw new IllegalStateException("Unsupported PEM object: " + obj);
            }
            return privateKey;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load GitHub App private key from "
                            + props.github().privateKeyPath(), e);
        }
    }

    /** Installation access token, cached until ~1 min before expiry. */
    public String installationToken(long installationId) {
        CachedToken cached = tokenCache.get(installationId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plus(1, ChronoUnit.MINUTES))) {
            return cached.token();
        }
        Map<?, ?> resp = http.post()
                .uri("/app/installations/{id}/access_tokens", installationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + createAppJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Map.class);
        String token = (String) resp.get("token");
        tokenCache.put(installationId,
                new CachedToken(token, Instant.now().plus(55, ChronoUnit.MINUTES)));
        return token;
    }

    /** Lists all installations of this GitHub App */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> listInstallations() {
        return http.get()
                .uri("/app/installations?per_page=100")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + createAppJwt())
                .retrieve()
                .body(java.util.List.class);
    }
}

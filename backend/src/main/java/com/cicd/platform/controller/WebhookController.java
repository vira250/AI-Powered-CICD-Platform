package com.cicd.platform.controller;

import com.cicd.platform.service.CICDService;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final CICDService cicdService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Value("${github.app.webhook-secret}")
    private String webhookSecret;

    public WebhookController(CICDService cicdService) {
        this.cicdService = cicdService;
    }

    @PostMapping("/github")
    public ResponseEntity<String> handleGitHubWebhook(
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {

        log.info("Received GitHub Webhook Event: {}", eventType);

        if (eventType == null || signature == null) {
            return ResponseEntity.badRequest().body("Missing headers");
        }

        // Verify HMAC signature
        if (!verifySignature(payload, signature)) {
            log.warn("Invalid webhook signature!");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        try {
            JsonNode jsonPayload = objectMapper.readTree(payload);
            Long installationId = jsonPayload.path("installation").path("id").asLong();

            if (installationId == 0) {
                log.info("Ignoring event without installation_id");
                return ResponseEntity.ok("Ignored");
            }

            if ("push".equals(eventType)) {
                String headSha = jsonPayload.path("after").asText();
                String owner = jsonPayload.path("repository").path("owner").path("login").asText();
                String repo = jsonPayload.path("repository").path("name").asText();

                // Skip if a branch was deleted
                if (!"0000000000000000000000000000000000000000".equals(headSha)) {
                    cicdService.triggerPipeline(installationId, owner, repo, headSha);
                }

            } else if ("pull_request".equals(eventType)) {
                String action = jsonPayload.path("action").asText();
                if ("opened".equals(action) || "synchronize".equals(action)) {
                    String headSha = jsonPayload.path("pull_request").path("head").path("sha").asText();
                    String owner = jsonPayload.path("repository").path("owner").path("login").asText();
                    String repo = jsonPayload.path("repository").path("name").asText();
                    
                    cicdService.triggerPipeline(installationId, owner, repo, headSha);
                }
            } else if ("installation".equals(eventType) || "installation_repositories".equals(eventType)) {
                log.info("App installation event received");
            } else {
                log.info("Unhandled event type: {}", eventType);
            }

            return ResponseEntity.ok("Event accepted");
        } catch (Exception e) {
            log.error("Error processing webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Processing failed");
        }
    }

    private boolean verifySignature(String payload, String signatureHeader) {
        if (!signatureHeader.startsWith("sha256=")) {
            return false;
        }
        String expectedHash = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, webhookSecret).hmacHex(payload);
        String actualHash = signatureHeader.substring(7);
        return expectedHash.equals(actualHash);
    }
}

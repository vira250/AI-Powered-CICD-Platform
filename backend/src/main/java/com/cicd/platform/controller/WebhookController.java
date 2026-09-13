package com.cicd.platform.controller;

import com.cicd.platform.service.AgentOrchestratorService;
import com.cicd.platform.service.CICDService;
import com.cicd.platform.service.GitHubAppService;
import com.cicd.platform.service.GitHubService;
import com.cicd.platform.service.LogAnalysisPersistenceService;
import com.cicd.platform.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final CICDService cicdService;
    private final AgentOrchestratorService orchestratorService;
    private final GitHubService gitHubService;
    private final GitHubAppService gitHubAppService;
    private final LogAnalysisPersistenceService logAnalysisPersistenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${github.app.webhook-secret}")
    private String webhookSecret;

    public WebhookController(CICDService cicdService,
                             AgentOrchestratorService orchestratorService,
                             GitHubService gitHubService,
                             GitHubAppService gitHubAppService,
                             LogAnalysisPersistenceService logAnalysisPersistenceService) {
        this.cicdService = cicdService;
        this.orchestratorService = orchestratorService;
        this.gitHubService = gitHubService;
        this.gitHubAppService = gitHubAppService;
        this.logAnalysisPersistenceService = logAnalysisPersistenceService;
    }

    @PostMapping("/github")
    public ResponseEntity<String> handleGitHubWebhook(
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {

        log.info("Received GitHub Webhook Event: {}", eventType);

        if (eventType == null || (signature == null && webhookSecret != null && !webhookSecret.isBlank())) {
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

            // ── EVENT 1: PUSH ──────────────────────────────────────────────
            if ("push".equals(eventType)) {
                String headSha = jsonPayload.path("after").asText();
                String owner = jsonPayload.path("repository").path("owner").path("login").asText();
                String repo = jsonPayload.path("repository").path("name").asText();

                if (!"0000000000000000000000000000000000000000".equals(headSha) && installationId != 0) {
                    cicdService.triggerPipeline(installationId, owner, repo, headSha);
                }

            // ── EVENT 2: PULL REQUEST (Trigger Code Review Agent) ───────────
            } else if ("pull_request".equals(eventType)) {
                String action = jsonPayload.path("action").asText();
                if ("opened".equals(action) || "synchronize".equals(action) || "reopened".equals(action)) {
                    int prNumber = jsonPayload.path("pull_request").path("number").asInt();
                    String owner = jsonPayload.path("repository").path("owner").path("login").asText();
                    String repo = jsonPayload.path("repository").path("name").asText();
                    String title = jsonPayload.path("pull_request").path("title").asText();
                    String author = jsonPayload.path("pull_request").path("user").path("login").asText();

                    log.info("Triggering Code Review Agent for PR {}/{} #{}", owner, repo, prNumber);

                    try {
                        String token = installationId != 0 ? gitHubAppService.getInstallationToken(installationId) : null;
                        String diff = "";
                        if (token != null) {
                            diff = gitHubService.fetchPullRequestDiff(token, owner, repo, prNumber);
                        } else {
                            diff = "Unified diff for " + owner + "/" + repo + " PR #" + prNumber;
                        }

                        orchestratorService.runPullRequestReview(
                                null, owner, repo, prNumber, diff, title, author, token
                        );
                    } catch (Exception ex) {
                        log.error("Failed to run automated PR code review", ex);
                    }
                }

            // ── EVENT 3: WORKFLOW RUN (Pipeline Result -> Multi-Agent Loops) ─
            } else if ("workflow_run".equals(eventType)) {
                String action = jsonPayload.path("action").asText();
                if ("completed".equals(action)) {
                    String conclusion = jsonPayload.path("workflow_run").path("conclusion").asText();
                    String owner = jsonPayload.path("repository").path("owner").path("login").asText();
                    String repo = jsonPayload.path("repository").path("name").asText();
                    String headSha = jsonPayload.path("workflow_run").path("head_sha").asText();
                    String branch = jsonPayload.path("workflow_run").path("head_branch").asText("main");
                    long runId = jsonPayload.path("workflow_run").path("id").asLong(0);

                    if ("failure".equalsIgnoreCase(conclusion)) {
                        log.info("Workflow run FAILED for {}/{} — Triggering Self-Healing Loop (Log Analysis -> Pipeline Gen)", owner, repo);
                        try {
                            if (installationId == 0 || runId == 0) {
                                throw new IllegalStateException("Workflow failure webhook is missing installation or run ID");
                            }

                            User integrationUser = new User();
                            integrationUser.setInstallationId(installationId);
                            List<Map<String, Object>> jobs = gitHubService.listWorkflowJobs(
                                    integrationUser, owner, repo, runId
                            );
                            List<Map<String, Object>> failedJobs = jobs.stream()
                                    .filter(job -> "failure".equalsIgnoreCase(String.valueOf(job.get("conclusion"))))
                                    .toList();
                            if (failedJobs.isEmpty()) {
                                log.warn("Workflow run {}/{} has no failed jobs with retrievable logs", owner, repo);
                                return ResponseEntity.ok("Event accepted");
                            }

                            String realLogs = failedJobs.stream()
                                    .map(job -> {
                                        long jobId = ((Number) job.get("id")).longValue();
                                        try {
                                        String jobLogs = gitHubService.getWorkflowJobLogs(
                                            integrationUser, owner, repo, jobId
                                        );
                                        log.info("Retrieved logs for failed GitHub Actions job {} ({})",
                                            jobId, job.get("name"));
                                        return "JOB: " + job.get("name") + "\n" + jobLogs;
                                        } catch (Exception jobException) {
                                            log.error("Failed to analyze GitHub Actions job {}", jobId, jobException);
                                            return "";
                                        }
                                    })
                                    .filter(logText -> !logText.isBlank())
                                    .collect(Collectors.joining("\n\n"));
                            if (realLogs.isBlank()) {
                                throw new IllegalStateException("GitHub returned no logs for failed jobs");
                            }

                            Map<String, Object> analysis = orchestratorService.analyzeLogs(realLogs);
                            log.info("Analyzed failed GitHub Actions run {}/{} as {}",
                                    owner, repo, analysis.get("error_type"));
                            for (Map<String, Object> failedJob : failedJobs) {
                                long jobId = ((Number) failedJob.get("id")).longValue();
                                try {
                                    logAnalysisPersistenceService.save(owner, repo, runId, jobId, analysis);
                                } catch (Exception persistenceException) {
                                    log.error("Failed to persist analysis for GitHub Actions job {}", jobId,
                                            persistenceException);
                                }
                            }

                            String failedYaml = "name: CI\non: [push]\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n      - run: npm test\n";
                            orchestratorService.remediatePipeline(integrationUser, owner, repo, branch, failedYaml, realLogs);
                        } catch (Exception ex) {
                            log.error("Failed self-healing pipeline remediation", ex);
                        }

                    } else if ("success".equalsIgnoreCase(conclusion)) {
                        log.info("Workflow run SUCCESS for {}/{} — Triggering Deployment Agent", owner, repo);
                        try {
                            orchestratorService.executeDeployment(
                                    null, owner, repo, headSha, "production", headSha.substring(0, Math.min(7, headSha.length())), false
                            );
                        } catch (Exception ex) {
                            log.error("Failed automated deployment trigger", ex);
                        }
                    }
                }

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
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return true;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expectedHash = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            String actualHash = signatureHeader.substring(7);
            return MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.UTF_8), actualHash.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error verifying webhook signature", e);
            return false;
        }
    }
}

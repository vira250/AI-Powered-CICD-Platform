package com.aicicd.platform.webhook;

import com.aicicd.platform.config.AppProperties;
import com.aicicd.platform.github.GitHubApiClient;
import com.aicicd.platform.github.GitHubAppService;
import com.aicicd.platform.orchestrator.OrchestratorClient;
import com.aicicd.platform.pipeline.AnalysisReport;
import com.aicicd.platform.pipeline.AnalysisReportRepository;
import com.aicicd.platform.repo.ConnectedRepository;
import com.aicicd.platform.repo.ConnectedRepositoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * GitHub App webhook receiver. Events handled (see Main FlowChart):
 *  - installation / installation_repositories -> sync repos into repo_db
 *  - pull_request (opened/synchronize)        -> Code Review + Security agents
 *  - workflow_run (completed)                 -> success: mark pipeline;
 *                                               failure: Log Analysis agent
 *                                               (+ Code Review fix if simple)
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final AppProperties props;
    private final GitHubAppService appService;
    private final GitHubApiClient github;
    private final OrchestratorClient orchestrator;
    private final ConnectedRepositoryRepository repos;
    private final AnalysisReportRepository reports;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebhookController(AppProperties props, GitHubAppService appService,
                             GitHubApiClient github, OrchestratorClient orchestrator,
                             ConnectedRepositoryRepository repos,
                             AnalysisReportRepository reports) {
        this.props = props;
        this.appService = appService;
        this.github = github;
        this.orchestrator = orchestrator;
        this.repos = repos;
        this.reports = reports;
    }

    @PostMapping("/github")
    public ResponseEntity<?> receive(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader("X-GitHub-Event") String event,
            @RequestBody byte[] rawBody) throws Exception {

        if (!verifySignature(signature, rawBody)) {
            return ResponseEntity.status(401).body(Map.of("error", "bad signature"));
        }
        JsonNode payload = mapper.readTree(rawBody);
        log.info("GitHub webhook event: {}", event);

        switch (event) {
            case "installation", "installation_repositories" -> handleInstallation(payload);
            case "pull_request" -> handlePullRequest(payload);
            case "workflow_run" -> handleWorkflowRun(payload);
            default -> log.debug("Ignored event {}", event);
        }
        return ResponseEntity.ok(Map.of("received", event));
    }

    // ---------------------------------------------------------------- util
    private boolean verifySignature(String signature, byte[] body) throws Exception {
        String secret = props.github().webhookSecret();
        if (secret == null || secret.isBlank()) return true; // dev mode
        if (signature == null || !signature.startsWith("sha256=")) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        return expected.equals(signature);
    }

    // ------------------------------------------------------- installation
    @Transactional("repoTransactionManager")
    void handleInstallation(JsonNode payload) {
        long installationId = payload.path("installation").path("id").asLong();
        JsonNode reposNode = payload.has("repositories")
                ? payload.get("repositories")
                : payload.get("repositories_added");
        if (reposNode == null || installationId == 0) return;
        for (JsonNode r : reposNode) {
            String fullName = r.path("full_name").asText();
            repos.findByFullName(fullName).orElseGet(() -> {
                ConnectedRepository cr = new ConnectedRepository();
                cr.setInstallationId(installationId);
                cr.setFullName(fullName);
                cr.setOwner(fullName.split("/")[0]);
                cr.setName(fullName.split("/")[1]);
                cr.setDefaultBranch(r.path("default_branch").asText("main"));
                return repos.save(cr);
            });
        }
    }

    // ------------------------------------------------------- pull request
    void handlePullRequest(JsonNode payload) {
        String action = payload.path("action").asText();
        if (!action.equals("opened") && !action.equals("synchronize")) return;

        long installationId = payload.path("installation").path("id").asLong();
        String fullName = payload.path("repository").path("full_name").asText();
        int prNumber = payload.path("pull_request").path("number").asInt();
        if (installationId == 0 || fullName.isEmpty()) return;

        String token = appService.installationToken(installationId);
        String diff = github.getPullRequestDiff(token, fullName, prNumber);

        Map<String, Object> result = orchestrator.orchestrate("review_pull_request",
                Map.of("diff", diff));

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        if (output == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) output.get("summary");
        StringBuilder comment = new StringBuilder("## AI Code Review\n\n");
        if (summary != null) {
            comment.append("| Severity | Count |\n|---|---|\n");
            summary.forEach((k, v) -> comment.append("| ").append(k)
                    .append(" | ").append(v).append(" |\n"));
        }
        @SuppressWarnings("unchecked")
        Map<String, List<String>> buckets =
                (Map<String, List<String>>) output.get("report");
        if (buckets != null) {
            buckets.forEach((sev, items) -> {
                if (items != null && !items.isEmpty()) {
                    comment.append("\n### ").append(sev.toUpperCase()).append('\n');
                    items.forEach(i -> comment.append("- ").append(i).append('\n'));
                }
            });
        }
        github.postPrComment(token, fullName, prNumber, comment.toString());

        // persist a copy for the dashboard
        repos.findByFullName(fullName).ifPresent(repo -> {
            try {
                AnalysisReport report = new AnalysisReport();
                report.setRepository(repo);
                report.setAgent("code_review");
                report.setReviewReportJson(mapper.writeValueAsString(output));
                report.setRootCause("PR #" + prNumber + " review");
                reports.save(report);
            } catch (Exception e) {
                log.warn("Failed to persist review report: {}", e.getMessage());
            }
        });
    }

    // ------------------------------------------------------- workflow run
    void handleWorkflowRun(JsonNode payload) {
        if (!"completed".equals(payload.path("action").asText())) return;

        long installationId = payload.path("installation").path("id").asLong();
        String fullName = payload.path("repository").path("full_name").asText();
        JsonNode run = payload.path("workflow_run");
        long runId = run.path("id").asLong();
        String conclusion = run.path("conclusion").asText();
        if (installationId == 0 || fullName.isEmpty()) return;

        if ("failure".equals(conclusion)) {
            String token = appService.installationToken(installationId);
            StringBuilder logs = new StringBuilder();
            String failedJob = "unknown";
            for (Map<String, Object> job : github.listRunJobs(token, fullName, runId)) {
                if ("failure".equals(job.get("conclusion"))) {
                    failedJob = String.valueOf(job.get("name"));
                    logs.append("== Job: ").append(failedJob).append(" ==\n")
                            .append(github.downloadJobLogs(token, fullName,
                                    ((Number) job.get("id")).longValue()))
                            .append('\n');
                }
            }

            Map<String, Object> result = orchestrator.orchestrate("pipeline_failed",
                    Map.of("logs", logs.toString(), "job_name", failedJob));

            repos.findByFullName(fullName).ifPresent(repo -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> output =
                            (Map<String, Object>) result.get("output");
                    if (output == null) return;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> analysis =
                            (Map<String, Object>) output.get("failure_analysis");
                    AnalysisReport report = new AnalysisReport();
                    report.setRepository(repo);
                    report.setRunId(runId);
                    report.setAgent("log_analysis");
                    if (analysis != null) {
                        report.setRootCause((String) analysis.get("root_cause"));
                        report.setImpact((String) analysis.get("impact"));
                        report.setSuggestedFix((String) analysis.get("suggested_fix"));
                        Object conf = analysis.get("confidence");
                        report.setConfidence(conf instanceof Number n ? n.intValue() : null);
                        report.setSimpleFix(Boolean.TRUE.equals(analysis.get("simple_fix")));
                    }
                    report.setReviewReportJson(mapper.writeValueAsString(output));
                    reports.save(report);

                    // Check if YAML was regenerated (YAML Error Feedback Loop)
                    String regeneratedYaml = (String) output.get("regenerated_yaml");
                    String regeneratedPath = (String) output.get("regenerated_path");
                    if (regeneratedYaml != null && !regeneratedYaml.isEmpty()) {
                        String path = regeneratedPath != null ? regeneratedPath : ".github/workflows/ai-ci-cd.yml";
                        log.info("YAML error detected. Pushing regenerated YAML to {}", path);
                        github.commitFile(token, fullName, path, regeneratedYaml,
                                "fix: auto-regenerate CI/CD pipeline due to YAML error [ai-cicd-platform]",
                                repo.getDefaultBranch());
                    }
                } catch (Exception e) {
                    log.warn("Failed to persist failure analysis: {}", e.getMessage());
                }
            });
        }
        // "success" -> the frontend/deployment flow takes over from here
        // (Deployment Agent build/push/deploy + health check + rollback).
    }
}

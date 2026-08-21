package com.aicicd.platform.pipeline;

import com.aicicd.platform.github.GitHubApiClient;
import com.aicicd.platform.github.GitHubAppService;
import com.aicicd.platform.orchestrator.OrchestratorClient;
import com.aicicd.platform.repo.ConnectedRepository;
import com.aicicd.platform.repo.ConnectedRepositoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Pipeline monitoring + on-demand failure analysis. */
@RestController
@RequestMapping("/api")
public class PipelineController {

    private final PipelineRepository pipelines;
    private final AnalysisReportRepository reports;
    private final ConnectedRepositoryRepository repos;
    private final GitHubAppService appService;
    private final GitHubApiClient github;
    private final OrchestratorClient orchestrator;
    private final ObjectMapper mapper = new ObjectMapper();

    public PipelineController(PipelineRepository pipelines,
                              AnalysisReportRepository reports,
                              ConnectedRepositoryRepository repos,
                              GitHubAppService appService,
                              GitHubApiClient github,
                              OrchestratorClient orchestrator) {
        this.pipelines = pipelines;
        this.reports = reports;
        this.repos = repos;
        this.appService = appService;
        this.github = github;
        this.orchestrator = orchestrator;
    }

    @GetMapping("/pipelines")
    public List<Map<String, Object>> listAllPipelines() {
        return pipelines.findAll().stream()
                .map(p -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("id", p.getId());
                    map.put("repositoryId", p.getRepository().getId());
                    map.put("repositoryFullName", p.getRepository().getFullName());
                    map.put("workflowPath", p.getWorkflowPath() != null ? p.getWorkflowPath() : "");
                    map.put("templateUsed", p.getTemplateUsed() != null ? p.getTemplateUsed() : "");
                    map.put("status", p.getStatus().name());
                    map.put("creditsUsed", p.getCreditsUsed() != null ? p.getCreditsUsed() : 0.0);
                    map.put("totalTokens", p.getTotalTokens() != null ? p.getTotalTokens() : 0);
                    map.put("createdAt", p.getCreatedAt().toString());
                    return map;
                })
                .toList();
    }

    @GetMapping("/repos/{repoId}/pipelines")
    public List<Map<String, Object>> listPipelines(@PathVariable Long repoId) {
        return pipelines.findByRepositoryIdOrderByCreatedAtDesc(repoId).stream()
                .map(p -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("id", p.getId());
                    map.put("workflowPath", p.getWorkflowPath() != null ? p.getWorkflowPath() : "");
                    map.put("templateUsed", p.getTemplateUsed() != null ? p.getTemplateUsed() : "");
                    map.put("status", p.getStatus().name());
                    map.put("createdAt", p.getCreatedAt().toString());
                    map.put("stackJson", p.getStackJson() != null ? p.getStackJson() : "{}");
                    map.put("creditsUsed", p.getCreditsUsed() != null ? p.getCreditsUsed() : 0.0);
                    map.put("totalTokens", p.getTotalTokens() != null ? p.getTotalTokens() : 0);
                    return map;
                })
                .toList();
    }

    @GetMapping("/pipelines/{id}")
    public ResponseEntity<?> getPipeline(@PathVariable Long id) {
        return pipelines.findById(id)
                .<ResponseEntity<?>>map(p -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("id", p.getId());
                    map.put("status", p.getStatus().name());
                    map.put("workflowPath", String.valueOf(p.getWorkflowPath()));
                    map.put("templateUsed", String.valueOf(p.getTemplateUsed()));
                    map.put("workflowYaml", String.valueOf(p.getWorkflowYaml()));
                    map.put("stackJson", String.valueOf(p.getStackJson()));
                    map.put("creditsUsed", p.getCreditsUsed() != null ? p.getCreditsUsed() : 0.0);
                    map.put("totalTokens", p.getTotalTokens() != null ? p.getTotalTokens() : 0);
                    return ResponseEntity.ok(map);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** Live GitHub Actions runs for a connected repo. */
    @GetMapping("/repos/{repoId}/runs")
    public ResponseEntity<?> listRuns(@PathVariable Long repoId) {
        ConnectedRepository repo = repos.findById(repoId)
                .orElseThrow(() -> new IllegalArgumentException("repo not connected"));
        String token = appService.installationToken(repo.getInstallationId());
        return ResponseEntity.ok(github.listWorkflowRuns(token, repo.getFullName()));
    }

    /** Manually trigger Log Analysis for a failed run (also fired by webhook). */
    @PostMapping("/repos/{repoId}/runs/{runId}/analyze")
    @Transactional("repoTransactionManager")
    public ResponseEntity<?> analyzeRun(@PathVariable Long repoId,
                                        @PathVariable Long runId) throws Exception {
        ConnectedRepository repo = repos.findById(repoId)
                .orElseThrow(() -> new IllegalArgumentException("repo not connected"));
        String token = appService.installationToken(repo.getInstallationId());

        // collect logs of failed jobs
        StringBuilder logs = new StringBuilder();
        String failedJob = null;
        for (Map<String, Object> job : github.listRunJobs(token, repo.getFullName(), runId)) {
            if ("failure".equals(job.get("conclusion"))) {
                failedJob = (String) job.get("name");
                long jobId = ((Number) job.get("id")).longValue();
                logs.append("== Job: ").append(failedJob).append(" ==\n");
                logs.append(github.downloadJobLogs(token, repo.getFullName(), jobId))
                        .append('\n');
            }
        }

        // Collect repository context, files and previous workflow YAML
        Map<String, Object> repoContext = github.getRepositoryContext(token, repo.getFullName(), repo.getDefaultBranch());
        List<String> files = github.listFiles(token, repo.getFullName(), repo.getDefaultBranch());
        List<PipelineEntity> existingPipelines = pipelines.findByRepositoryIdOrderByCreatedAtDesc(repoId);
        String previousYaml = existingPipelines.isEmpty() ? "" : existingPipelines.get(0).getWorkflowYaml();

        Map<String, Object> orchPayload = new java.util.HashMap<>();
        orchPayload.put("logs", logs.toString());
        orchPayload.put("job_name", failedJob != null ? failedJob : "unknown");
        orchPayload.put("files", files);
        orchPayload.put("repo_context", repoContext);
        if (previousYaml != null && !previousYaml.isBlank()) {
            orchPayload.put("workflow_yaml", previousYaml);
        }

        Map<String, Object> result = orchestrator.orchestrate("pipeline_failed", orchPayload);

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        @SuppressWarnings("unchecked")
        Map<String, Object> analysis = output != null
                ? (Map<String, Object>) output.get("failure_analysis") : null;
        if (analysis == null) return ResponseEntity.badRequest().body(result);

        AnalysisReport report = new AnalysisReport();
        report.setRepository(repo);
        report.setRunId(runId);
        report.setAgent("log_analysis");
        report.setRootCause((String) analysis.get("root_cause"));
        report.setImpact((String) analysis.get("impact"));
        report.setSuggestedFix((String) analysis.get("suggested_fix"));
        Object conf = analysis.get("confidence");
        report.setConfidence(conf instanceof Number n ? n.intValue() : null);
        report.setSimpleFix(Boolean.TRUE.equals(analysis.get("simple_fix")));
        report.setErrorExcerpt(String.join("\n",
                ((List<?>) analysis.getOrDefault("error_excerpt", List.of()))
                        .stream().map(String::valueOf).toList()));
        report.setReviewReportJson(mapper.writeValueAsString(output));
        reports.save(report);

        Map<String, Object> respMap = new java.util.HashMap<>();
        respMap.put("reportId", report.getId());
        respMap.put("analysis", analysis);
        if (output.get("proposed_fix") != null) {
            respMap.put("proposedFix", output.get("proposed_fix"));
        }

        String regeneratedYaml = (String) output.get("regenerated_yaml");
        String regeneratedPath = (String) output.getOrDefault("regenerated_path", ".github/workflows/ai-ci-cd.yml");
        boolean autoPushed = false;

        if (regeneratedYaml != null && !regeneratedYaml.isBlank()) {
            PipelineEntity healed = new PipelineEntity();
            healed.setRepository(repo);
            healed.setWorkflowPath(regeneratedPath);
            healed.setWorkflowYaml(regeneratedYaml);
            healed.setTemplateUsed((String) output.get("template_used"));
            healed.setStackJson(mapper.writeValueAsString(output.get("stack")));
            if (output.get("credits_used") instanceof Number n) {
                healed.setCreditsUsed(n.doubleValue());
            }
            if (output.get("total_tokens") instanceof Number n) {
                healed.setTotalTokens(n.intValue());
            }
            healed.setStatus(PipelineEntity.Status.PUSHED);
            healed.setPushedAt(java.time.Instant.now());
            pipelines.save(healed);

            try {
                github.commitFile(token, repo.getFullName(), regeneratedPath, regeneratedYaml,
                        "fix(ci): auto-remediated CI/CD workflow from build failure [ai-cicd-platform]",
                        repo.getDefaultBranch());
                autoPushed = true;
                respMap.put("newPipelineId", healed.getId());
            } catch (Exception ex) {
                respMap.put("pushError", ex.getMessage());
            }
        }
        respMap.put("autoPushed", autoPushed);
        respMap.put("regeneratedYaml", regeneratedYaml);

        return ResponseEntity.ok(respMap);
    }

    @GetMapping("/repos/{repoId}/reports")
    public List<AnalysisReport> listReports(@PathVariable Long repoId) {
        return reports.findByRepositoryIdOrderByCreatedAtDesc(repoId);
    }
}

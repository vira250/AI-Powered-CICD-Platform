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
                .map(p -> Map.<String, Object>of(
                        "id", p.getId(),
                        "repositoryId", p.getRepository().getId(),
                        "repositoryFullName", p.getRepository().getFullName(),
                        "workflowPath", p.getWorkflowPath() != null ? p.getWorkflowPath() : "",
                        "templateUsed", p.getTemplateUsed() != null ? p.getTemplateUsed() : "",
                        "status", p.getStatus().name(),
                        "createdAt", p.getCreatedAt().toString()))
                .toList();
    }

    @GetMapping("/repos/{repoId}/pipelines")
    public List<Map<String, Object>> listPipelines(@PathVariable Long repoId) {
        return pipelines.findByRepositoryIdOrderByCreatedAtDesc(repoId).stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.getId(),
                        "workflowPath", p.getWorkflowPath() != null ? p.getWorkflowPath() : "",
                        "templateUsed", p.getTemplateUsed() != null ? p.getTemplateUsed() : "",
                        "status", p.getStatus().name(),
                        "createdAt", p.getCreatedAt().toString(),
                        "stackJson", p.getStackJson() != null ? p.getStackJson() : "{}"))
                .toList();
    }

    @GetMapping("/pipelines/{id}")
    public ResponseEntity<?> getPipeline(@PathVariable Long id) {
        return pipelines.findById(id)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(Map.of(
                        "id", p.getId(),
                        "status", p.getStatus().name(),
                        "workflowPath", String.valueOf(p.getWorkflowPath()),
                        "templateUsed", String.valueOf(p.getTemplateUsed()),
                        "workflowYaml", String.valueOf(p.getWorkflowYaml()),
                        "stackJson", String.valueOf(p.getStackJson()))))
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

        Map<String, Object> result = orchestrator.orchestrate("pipeline_failed",
                Map.of("logs", logs.toString(), "job_name",
                        failedJob != null ? failedJob : "unknown"));

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

        return ResponseEntity.ok(Map.of("reportId", report.getId(), "analysis", analysis,
                "proposedFix", output.getOrDefault("proposed_fix", Map.of())));
    }

    @GetMapping("/repos/{repoId}/reports")
    public List<AnalysisReport> listReports(@PathVariable Long repoId) {
        return reports.findByRepositoryIdOrderByCreatedAtDesc(repoId);
    }
}

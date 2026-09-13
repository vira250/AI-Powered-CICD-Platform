package com.cicd.platform.service;

import com.cicd.platform.model.PipelineRun;
import com.cicd.platform.model.User;
import com.cicd.platform.repository.PipelineRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Correlates a generated workflow push to its GitHub Actions run and runs the
 * established Log Analyzer after that run reaches a terminal state. This is
 * deliberately independent of GitHub webhooks: a missing/delayed webhook must
 * not leave a generated pipeline without analysis.
 */
@Service
public class WorkflowLogAnalysisMonitor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowLogAnalysisMonitor.class);

    private final TaskExecutor executor;
    private final GitHubService gitHubService;
    private final LogAnalysisPersistenceService persistenceService;
    private final PipelineRunRepository pipelineRunRepository;
    private final AgentOrchestratorService orchestratorService;
    private final long pollIntervalMs;
    private final int maxPollAttempts;

    public WorkflowLogAnalysisMonitor(
            @Qualifier("workflowLogAnalysisExecutor") TaskExecutor executor,
            GitHubService gitHubService,
            LogAnalysisPersistenceService persistenceService,
            PipelineRunRepository pipelineRunRepository,
            @Lazy AgentOrchestratorService orchestratorService,
            @Value("${app.workflow-log-analysis.poll-interval-ms:5000}") long pollIntervalMs,
            @Value("${app.workflow-log-analysis.max-poll-attempts:120}") int maxPollAttempts) {
        this.executor = executor;
        this.gitHubService = gitHubService;
        this.persistenceService = persistenceService;
        this.pipelineRunRepository = pipelineRunRepository;
        this.orchestratorService = orchestratorService;
        this.pollIntervalMs = Math.max(1000, pollIntervalMs);
        this.maxPollAttempts = Math.max(1, maxPollAttempts);
    }

    public void monitorGeneratedWorkflow(PipelineRun pipelineRun, User user, String commitSha, String workflowPath) {
        if (pipelineRun.getId() == null || commitSha == null || commitSha.isBlank()) {
            log.warn("Cannot start automatic log analysis without a pipeline run id and pushed commit SHA");
            if (pipelineRun.getId() != null) {
                updateStatus(pipelineRun.getId(), "error", "GitHub did not return the workflow push commit SHA");
            }
            return;
        }
        executor.execute(() -> monitor(pipelineRun.getId(), user, commitSha, workflowPath));
    }

    private void monitor(long pipelineRunId, User user, String commitSha, String workflowPath) {
        try {
            updateStatus(pipelineRunId, "waiting_for_workflow", "Waiting for the generated GitHub Actions workflow to start");
            Map<String, Object> workflowRun = waitForTerminalRun(user, pipelineRunId, commitSha, workflowPath);
            if (workflowRun == null) {
                updateStatus(pipelineRunId, "timed_out", "No terminal GitHub Actions run was found for the generated workflow commit");
                return;
            }

            long workflowRunId = number(workflowRun.get("id"));
            String conclusion = string(workflowRun.get("conclusion"));
            updateWorkflow(pipelineRunId, workflowRunId, conclusion);
            updateStatus(pipelineRunId, "analyzing", "Retrieving completed workflow job logs");

            analyzeTerminalRun(user, pipelineRunId, workflowRunId, conclusion,
                    string(workflowRun.get("owner")), string(workflowRun.get("repo")));
        } catch (Exception exception) {
            log.error("Automatic log analysis failed for generated pipeline {}", pipelineRunId, exception);
            updateStatus(pipelineRunId, "error", "Automatic log analysis failed: " + safeMessage(exception));
        }
    }

    private Map<String, Object> waitForTerminalRun(User user, long pipelineRunId, String commitSha, String workflowPath) {
        PipelineRun pipelineRun = pipelineRunRepository.findById(pipelineRunId).orElse(null);
        if (pipelineRun == null) {
            return null;
        }
        for (int attempt = 0; attempt < maxPollAttempts; attempt++) {
            List<Map<String, Object>> runs = gitHubService.listWorkflowRuns(user, pipelineRun.getOwner(), pipelineRun.getRepoName());
            for (Map<String, Object> listedRun : runs) {
                Map<String, Object> candidate = new LinkedHashMap<>(listedRun);
                if (!commitSha.equals(string(candidate.get("head_sha"))) || !isGeneratedWorkflow(candidate, workflowPath)) {
                    continue;
                }
                candidate.put("owner", pipelineRun.getOwner());
                candidate.put("repo", pipelineRun.getRepoName());
                if ("completed".equalsIgnoreCase(string(candidate.get("status")))) {
                    return candidate;
                }
                updateStatus(pipelineRunId, "waiting_for_completion", "Generated GitHub Actions workflow is running");
            }
            pause();
        }
        return null;
    }

    private boolean isGeneratedWorkflow(Map<String, Object> run, String workflowPath) {
        String path = string(run.get("path"));
        // GitHub's run-list API returns the workflow path as either the exact
        // repository path or path@ref, depending on the API response/version.
        return workflowPath.equals(path) || path.startsWith(workflowPath + "@");
    }

    private void analyzeTerminalRun(User user, long pipelineRunId, long workflowRunId, String conclusion,
                                    String owner, String repo) {
        // GitHub can mark the workflow run completed a moment before jobs appear, or
        // report job conclusions other than the literal "failure" (timed_out, startup_failure).
        // The manual Log Analyzer lists every job from this same API; reuse that set and
        // only prefer failed jobs when they are present.
        List<Map<String, Object>> jobs = gitHubService.listWorkflowJobs(user, owner, repo, workflowRunId);
        for (int attempt = 0; attempt < 5 && jobs.isEmpty(); attempt++) {
            pause();
            jobs = gitHubService.listWorkflowJobs(user, owner, repo, workflowRunId);
        }
        List<Map<String, Object>> relevantJobs = selectAnalyzableJobs(jobs, conclusion);
        if (relevantJobs.isEmpty()) {
            log.warn("Workflow run {} completed as {} with {} GitHub job(s); none were analyzable",
                    workflowRunId, conclusion, jobs.size());
            updateStatus(pipelineRunId, "no_relevant_jobs", "The completed workflow had no analyzable jobs");
            return;
        }

        int analyzed = 0;
        List<String> failures = new ArrayList<>();
        Long firstJobId = null;
        for (Map<String, Object> job : relevantJobs) {
            long jobId = number(job.get("id"));
            if (jobId <= 0) {
                continue;
            }
            if (firstJobId == null) {
                firstJobId = jobId;
            }
            // A completed workflow webhook may already have analyzed a failed job.
            // Reusing that persisted result keeps this trigger idempotent when both paths are enabled.
            if (persistenceService.findLatest(owner, repo, workflowRunId, jobId).isPresent()) {
                analyzed++;
                continue;
            }
            try {
                String logs = gitHubService.getWorkflowJobLogs(user, owner, repo, jobId);
                if (logs.isBlank()) {
                    failures.add("Job " + jobId + " returned no logs");
                    continue;
                }
                Map<String, Object> analysis = new LinkedHashMap<>(orchestratorService.analyzeLogs(logs));
                analysis.put("source", "github_actions");
                analysis.put("owner", owner);
                analysis.put("repo", repo);
                analysis.put("workflow_run_id", workflowRunId);
                analysis.put("job_id", jobId);
                analysis.put("workflow_conclusion", conclusion);
                analysis.put("job_name", string(job.get("name")));
                persistenceService.save(owner, repo, workflowRunId, jobId, analysis);
                analyzed++;
            } catch (Exception exception) {
                log.error("Automatic log analysis failed for job {} in workflow run {}", jobId, workflowRunId, exception);
                failures.add("Job " + jobId + ": " + safeMessage(exception));
            }
        }

        setAnalysisJob(pipelineRunId, firstJobId);
        if (analyzed == relevantJobs.size()) {
            updateStatus(pipelineRunId, "completed", "Log Analyzer completed for " + analyzed + " workflow job(s)");
        } else if (analyzed > 0) {
            updateStatus(pipelineRunId, "completed_with_errors", "Analyzed " + analyzed + " job(s); " + String.join("; ", failures));
        } else {
            updateStatus(pipelineRunId, "error", String.join("; ", failures));
        }
    }

    private List<Map<String, Object>> selectAnalyzableJobs(List<Map<String, Object>> jobs, String workflowConclusion) {
        List<Map<String, Object>> analyzable = jobs.stream()
                .filter(job -> number(job.get("id")) > 0)
                .filter(job -> !isSkipped(job))
                .toList();
        if (isFailedWorkflow(workflowConclusion)) {
            List<Map<String, Object>> failed = analyzable.stream().filter(this::isFailedJob).toList();
            return failed.isEmpty() ? analyzable : failed;
        }
        List<Map<String, Object>> completed = analyzable.stream()
                .filter(job -> "completed".equalsIgnoreCase(normalize(job.get("status")))
                        || !normalize(job.get("conclusion")).isEmpty())
                .toList();
        return completed.isEmpty() ? analyzable : completed;
    }

    private boolean isFailedWorkflow(String conclusion) {
        String value = normalize(conclusion);
        return "failure".equalsIgnoreCase(value)
                || "timed_out".equalsIgnoreCase(value)
                || "startup_failure".equalsIgnoreCase(value)
                || "cancelled".equalsIgnoreCase(value);
    }

    private boolean isFailedJob(Map<String, Object> job) {
        String conclusion = normalize(job.get("conclusion"));
        return "failure".equalsIgnoreCase(conclusion)
                || "timed_out".equalsIgnoreCase(conclusion)
                || "startup_failure".equalsIgnoreCase(conclusion);
    }

    private boolean isSkipped(Map<String, Object> job) {
        return "skipped".equalsIgnoreCase(normalize(job.get("conclusion")));
    }

    private String normalize(Object value) {
        String text = string(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text) || "none".equalsIgnoreCase(text)) {
            return "";
        }
        return text;
    }

    private void updateWorkflow(long pipelineRunId, long workflowRunId, String conclusion) {
        pipelineRunRepository.findById(pipelineRunId).ifPresent(run -> {
            run.setWorkflowRunId(workflowRunId);
            run.setWorkflowConclusion(conclusion);
            pipelineRunRepository.save(run);
        });
    }

    private void setAnalysisJob(long pipelineRunId, Long jobId) {
        pipelineRunRepository.findById(pipelineRunId).ifPresent(run -> {
            run.setLogAnalysisJobId(jobId);
            pipelineRunRepository.save(run);
        });
    }

    private void updateStatus(long pipelineRunId, String status, String message) {
        pipelineRunRepository.findById(pipelineRunId).ifPresent(run -> {
            run.setLogAnalysisStatus(status);
            run.setLogAnalysisMessage(message);
            pipelineRunRepository.save(run);
        });
    }

    private void pause() {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workflow monitoring was interrupted", exception);
        }
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}

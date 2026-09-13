package com.cicd.platform.service;

import com.cicd.platform.dto.RepositoryContext;
import com.cicd.platform.model.CodeReviewResult;
import com.cicd.platform.model.DeploymentRecord;
import com.cicd.platform.model.PipelineRun;
import com.cicd.platform.model.User;
import com.cicd.platform.repository.CodeReviewResultRepository;
import com.cicd.platform.repository.DeploymentRecordRepository;
import com.cicd.platform.repository.PipelineRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorService.class);

    private final WebClient agentClient;
    private final RepositoryContextBuilder repositoryContextBuilder;
    private final PipelineRunRepository pipelineRunRepository;
    private final CodeReviewResultRepository codeReviewResultRepository;
    private final DeploymentRecordRepository deploymentRecordRepository;
    private final GitHubService gitHubService;
    private final ObjectMapper objectMapper;
    private final WorkflowLogAnalysisMonitor workflowLogAnalysisMonitor;
    private final LogAnalysisPersistenceService logAnalysisPersistenceService;

    public AgentOrchestratorService(
            @Value("${app.agents.url:http://localhost:8001}") String agentsUrl,
            RepositoryContextBuilder repositoryContextBuilder,
            PipelineRunRepository pipelineRunRepository,
            CodeReviewResultRepository codeReviewResultRepository,
            DeploymentRecordRepository deploymentRecordRepository,
            GitHubService gitHubService,
            ObjectMapper objectMapper,
            WorkflowLogAnalysisMonitor workflowLogAnalysisMonitor,
            LogAnalysisPersistenceService logAnalysisPersistenceService) {
        this.agentClient = WebClient.builder()
                .baseUrl(agentsUrl)
                .build();
        this.repositoryContextBuilder = repositoryContextBuilder;
        this.pipelineRunRepository = pipelineRunRepository;
        this.codeReviewResultRepository = codeReviewResultRepository;
        this.deploymentRecordRepository = deploymentRecordRepository;
        this.gitHubService = gitHubService;
        this.objectMapper = objectMapper;
        this.workflowLogAnalysisMonitor = workflowLogAnalysisMonitor;
        this.logAnalysisPersistenceService = logAnalysisPersistenceService;
    }

    /**
     * Generate a CI/CD pipeline for a repository and automatically push the .yml file to GitHub.
     */
    public Map<String, Object> generatePipeline(User user, String owner, String repo, String branch) {
        log.info("Generating pipeline for {}/{} (branch: {})", owner, repo, branch);

        RepositoryContext context = repositoryContextBuilder.buildRepositoryContext(user, owner, repo, branch);

        PipelineRun run = new PipelineRun();
        run.setUserId(user.getId());
        run.setOwner(owner);
        run.setRepoName(repo);
        run.setBranch(branch != null ? branch : "main");
        run.setCommitSha(context.getRepository() != null ? context.getRepository().getCommitSha() : null);
        run.setStatus("generating");
        run.setFilePath(".github/workflows/ci.yml");
        pipelineRunRepository.save(run);

        try {
            JsonNode response = agentClient.post()
                    .uri("/api/pipeline/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("context", objectMapper.convertValue(context, Map.class)))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null) {
                String yamlContent = response.path("yaml_content").asText("");
                String filePath = response.path("file_path").asText(".github/workflows/ci.yml");

                run.setYamlContent(yamlContent);
                run.setStatus(response.path("status").asText("success"));
                run.setGenerationTimeMs(response.path("generation_time_ms").asInt(0));
                run.setTechnologyJson(response.path("technology").toString());
                run.setValidationJson(response.path("validation").toString());
                run.setCompletedAt(LocalDateTime.now());

                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = objectMapper.convertValue(response, Map.class);

                // AUTOMATICALLY PUSH GENERATED .yml TO GITHUB
                if (yamlContent != null && !yamlContent.isBlank()) {
                    try {
                        log.info("Automatically pushing generated workflow {} to GitHub for {}/{} (branch: {})",
                                filePath, owner, repo, run.getBranch());
                        Map<String, Object> pushResult = gitHubService.pushFile(
                                user,
                                owner,
                                repo,
                                filePath,
                                yamlContent,
                                "ci: automated CI/CD pipeline generated by DeployHub AI",
                                run.getBranch()
                        );
                        String pushCommitSha = String.valueOf(pushResult.getOrDefault("commit_sha", ""));
                        run.setMessage("Pipeline generated and automatically pushed to GitHub (" + filePath + ")");
                        run.setPushedToGithub(true);
                        run.setPushCommitSha(pushCommitSha);
                        run.setLogAnalysisStatus("queued");
                        run.setLogAnalysisMessage("Waiting for the generated GitHub Actions workflow");
                        resultMap.put("auto_pushed", true);
                        resultMap.put("push_status", "pushed");
                        resultMap.put("push_result", pushResult);
                        resultMap.put("pipeline_run_id", run.getId());
                        pipelineRunRepository.save(run);
                        try {
                            workflowLogAnalysisMonitor.monitorGeneratedWorkflow(run, user, pushCommitSha, filePath);
                        } catch (Exception monitorException) {
                            run.setLogAnalysisStatus("error");
                            run.setLogAnalysisMessage("Unable to queue automatic log analysis: " + monitorException.getMessage());
                            pipelineRunRepository.save(run);
                            log.error("Unable to queue automatic log analysis for {}/{}", owner, repo, monitorException);
                        }
                        resultMap.put("log_analysis_status", run.getLogAnalysisStatus());
                        log.info("Successfully pushed {} to {}/{}; automatic log analysis monitor queued", filePath, owner, repo);
                    } catch (Exception pe) {
                        log.warn("Automatic push to GitHub failed: {}", pe.getMessage());
                        run.setMessage("Pipeline generated (Auto-push failed: " + pe.getMessage() + ")");
                        resultMap.put("auto_pushed", false);
                        resultMap.put("push_status", "push_failed");
                        resultMap.put("push_error", pe.getMessage());
                    }
                } else {
                    run.setMessage(response.path("message").asText(""));
                }

                pipelineRunRepository.save(run);
                resultMap.putIfAbsent("pipeline_run_id", run.getId());
                return resultMap;
            }

            throw new RuntimeException("Empty response from pipeline agent");

        } catch (Exception e) {
            run.setStatus("error");
            run.setMessage(e.getMessage());
            run.setCompletedAt(LocalDateTime.now());
            pipelineRunRepository.save(run);
            throw new RuntimeException("Pipeline generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Multi-Agent Self-Healing Loop:
     * Remediate a failed CI/CD pipeline using logs, root cause analysis, and auto-push the fixed YAML.
     */
    public Map<String, Object> remediatePipeline(User user, String owner, String repo, String branch,
                                                 String failedYaml, String errorLogs) {
        log.info("Self-healing pipeline for {}/{} using failure logs", owner, repo);

        PipelineRun run = new PipelineRun();
        if (user != null) {
            run.setUserId(user.getId());
        }
        run.setOwner(owner);
        run.setRepoName(repo);
        run.setBranch(branch != null ? branch : "main");
        run.setStatus("remediating");
        run.setFilePath(".github/workflows/ci.yml");
        pipelineRunRepository.save(run);

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("failed_yaml", failedYaml);
            payload.put("log_text", errorLogs);

            JsonNode response = agentClient.post()
                    .uri("/api/orchestrator/workflow")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "workflow", "pipeline_failure_remediation",
                            "payload", payload
                    ))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null) {
                String healedYaml = response.path("healed_yaml").asText("");
                boolean autoRemediated = response.path("auto_remediated").asBoolean(true);

                run.setYamlContent(healedYaml);
                run.setStatus(autoRemediated ? "success" : "warning");
                run.setValidationJson(response.path("validation").toString());
                run.setCompletedAt(LocalDateTime.now());

                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = objectMapper.convertValue(response, Map.class);

                // AUTOMATICALLY PUSH REMEDIATED .yml TO GITHUB
                if (healedYaml != null && !healedYaml.isBlank() && autoRemediated) {
                    try {
                        log.info("Automatically pushing remediated workflow {} to GitHub for {}/{} (branch: {})",
                                run.getFilePath(), owner, repo, run.getBranch());
                        Map<String, Object> pushResult = gitHubService.pushFile(
                                user,
                                owner,
                                repo,
                                run.getFilePath(),
                                healedYaml,
                                "ci: self-healed CI/CD pipeline generated by DeployHub AI",
                                run.getBranch()
                        );
                        run.setMessage("Self-healed pipeline generated and automatically pushed to GitHub");
                        resultMap.put("auto_pushed", true);
                        resultMap.put("push_status", "pushed");
                        resultMap.put("push_result", pushResult);
                        log.info("Successfully auto-pushed remediated workflow to {}/{}", owner, repo);
                    } catch (Exception pe) {
                        log.warn("Automatic push of remediated pipeline failed: {}", pe.getMessage());
                        run.setMessage("Self-healed: " + response.path("suggested_fix").asText(""));
                        resultMap.put("auto_pushed", false);
                        resultMap.put("push_status", "push_failed");
                        resultMap.put("push_error", pe.getMessage());
                    }
                } else {
                    run.setMessage("Self-healed: " + response.path("suggested_fix").asText(""));
                }

                pipelineRunRepository.save(run);
                return resultMap;
            }

            throw new RuntimeException("Empty response from multi-agent remediation workflow");

        } catch (Exception e) {
            run.setStatus("error");
            run.setMessage("Remediation failed: " + e.getMessage());
            run.setCompletedAt(LocalDateTime.now());
            pipelineRunRepository.save(run);
            throw new RuntimeException("Self-healing pipeline remediation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Run a code review on a repository context.
     */
    public Map<String, Object> runCodeReview(User user, String owner, String repo, String branch) {
        log.info("Running code review for {}/{} (branch: {})", owner, repo, branch);

        RepositoryContext context = repositoryContextBuilder.buildRepositoryContext(user, owner, repo, branch);

        try {
            JsonNode response = agentClient.post()
                    .uri("/api/review/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("context", objectMapper.convertValue(context, Map.class)))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null) {
                CodeReviewResult result = new CodeReviewResult();
                result.setUserId(user.getId());
                result.setOwner(owner);
                result.setRepoName(repo);
                result.setBranch(branch);
                result.setCommitSha(context.getRepository() != null ? context.getRepository().getCommitSha() : null);
                result.setReviewId(response.path("review_id").asText(""));
                result.setSummary(response.path("summary").asText(""));
                result.setVerdict(response.path("verdict").asText(""));
                result.setFindingsJson(response.path("findings").toString());
                result.setFilesReviewed(response.path("stats").path("files_reviewed").asInt(0));
                result.setFilesSkipped(response.path("stats").path("files_skipped").asInt(0));
                result.setFindingsCount(response.path("stats").path("findings_count").asInt(0));
                result.setStatus("success");
                result.setGenerationTimeMs(response.path("generation_time_ms").asInt(0));
                codeReviewResultRepository.save(result);

                return objectMapper.convertValue(response, Map.class);
            }

            throw new RuntimeException("Empty response from review agent");

        } catch (Exception e) {
            throw new RuntimeException("Code review failed: " + e.getMessage(), e);
        }
    }

    /**
     * Run code review on a Pull Request diff, save result, and post comment back to GitHub.
     */
    public Map<String, Object> runPullRequestReview(User user, String owner, String repo, int prNumber,
                                                    String diffText, String title, String author, String token) {
        log.info("Running PR review for {}/{} #{}", owner, repo, prNumber);

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("diff", diffText);
            body.put("owner", owner);
            body.put("repo", repo);
            body.put("pr_number", prNumber);
            body.put("title", title != null ? title : "Pull Request #" + prNumber);
            body.put("author", author != null ? author : "unknown");

            JsonNode response = agentClient.post()
                    .uri("/api/review/pull-request")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null) {
                CodeReviewResult result = new CodeReviewResult();
                if (user != null) {
                    result.setUserId(user.getId());
                }
                result.setOwner(owner);
                result.setRepoName(repo);
                result.setBranch("pr-" + prNumber);
                result.setReviewId(response.path("review_id").asText(""));
                result.setSummary(response.path("summary").asText(""));
                result.setVerdict(response.path("verdict").asText(""));
                result.setFindingsJson(response.path("findings").toString());
                result.setFilesReviewed(response.path("stats").path("files_reviewed").asInt(0));
                result.setFilesSkipped(response.path("stats").path("files_skipped").asInt(0));
                result.setFindingsCount(response.path("stats").path("findings_count").asInt(0));
                result.setStatus("success");
                result.setGenerationTimeMs(response.path("generation_time_ms").asInt(0));
                codeReviewResultRepository.save(result);

                // Post comment back to GitHub PR if token available
                String commentMarkdown = response.path("pr_comment_markdown").asText("");
                if (token != null && !token.isBlank() && !commentMarkdown.isBlank()) {
                    gitHubService.postPullRequestComment(token, owner, repo, prNumber, commentMarkdown);
                }

                return objectMapper.convertValue(response, Map.class);
            }

            throw new RuntimeException("Empty response from PR review agent");

        } catch (Exception e) {
            throw new RuntimeException("PR review failed: " + e.getMessage(), e);
        }
    }

    /**
     * Analyze pipeline logs.
     */
    public Map<String, Object> analyzeLogs(String logText) {
        log.info("Analyzing pipeline logs ({} chars)", logText.length());

        try {
            JsonNode response = agentClient.post()
                    .uri("/api/logs/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("log_text", logText))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null) {
                return objectMapper.convertValue(response, Map.class);
            }

            throw new RuntimeException("Empty response from log analysis agent");

        } catch (Exception e) {
            throw new RuntimeException("Log analysis failed: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> listWorkflowRuns(User user, String owner, String repo) {
        return gitHubService.listWorkflowRuns(user, owner, repo);
    }

    public List<Map<String, Object>> listWorkflowJobs(User user, String owner, String repo, long runId) {
        return gitHubService.listWorkflowJobs(user, owner, repo, runId);
    }

    public Map<String, Object> analyzeWorkflowJobLogs(User user, String owner, String repo, long jobId) {
        String logs = gitHubService.getWorkflowJobLogs(user, owner, repo, jobId);
        if (logs.isBlank()) {
            throw new RuntimeException("GitHub returned no logs for this job");
        }
        Map<String, Object> result = analyzeLogs(logs);
        result.put("source", "github_actions");
        result.put("owner", owner);
        result.put("repo", repo);
        result.put("job_id", jobId);
        return result;
    }

    /**
     * Returns the automatic Log Analyzer state and its persisted result for a
     * pipeline generation owned by the current user. The UI can poll this
     * without asking the user to choose a workflow run or job.
     */
    public Map<String, Object> getPipelineLogAnalysis(Long userId, long pipelineRunId) {
        PipelineRun run = pipelineRunRepository.findByIdAndUserId(pipelineRunId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Pipeline generation run not found"));
        Map<String, Object> result = new HashMap<>();
        result.put("pipeline_run_id", run.getId());
        result.put("status", run.getLogAnalysisStatus() != null ? run.getLogAnalysisStatus() : "not_started");
        result.put("message", run.getLogAnalysisMessage() != null ? run.getLogAnalysisMessage() : "");
        result.put("workflow_run_id", run.getWorkflowRunId());
        result.put("workflow_conclusion", run.getWorkflowConclusion());
        result.put("job_id", run.getLogAnalysisJobId());
        if (run.getWorkflowRunId() != null && run.getLogAnalysisJobId() != null) {
            logAnalysisPersistenceService.findLatest(run.getOwner(), run.getRepoName(),
                    run.getWorkflowRunId(), run.getLogAnalysisJobId()).ifPresent(analysis -> result.put("analysis", analysis));
        }
        return result;
    }

    /**
     * Execute production deployment lifecycle (Docker -> Deploy -> Health Check -> Rollback on failure).
     */
    public Map<String, Object> executeDeployment(User user, String owner, String repo, String commitSha,
                                                 String environment, String imageTag, boolean simulateFailure) {
        log.info("Executing deployment for {}/{} (commit: {}) to {}", owner, repo, commitSha, environment);

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("owner", owner);
            body.put("repo", repo);
            body.put("commit_sha", commitSha != null ? commitSha : "HEAD");
            body.put("environment", environment != null ? environment : "production");
            body.put("image_tag", imageTag);
            body.put("simulate_health_failure", simulateFailure);

            JsonNode response = agentClient.post()
                    .uri("/api/deploy/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null) {
                DeploymentRecord record = new DeploymentRecord();
                if (user != null) {
                    record.setUserId(user.getId());
                }
                record.setOwner(owner);
                record.setRepoName(repo);
                record.setCommitSha(commitSha);
                record.setVersion(response.path("version").asText("v1.0"));
                record.setEnvironment(response.path("environment").asText("production"));
                record.setImageTag(response.path("image_tag").asText(""));
                record.setStatus(response.path("status").asText("deployed"));
                record.setHealthCheckStatus(response.path("health_check_status").asText("healthy"));
                record.setHealthEndpoint(response.path("health_endpoint").asText("/health"));
                record.setRolledBack(response.path("rolled_back").asBoolean(false));
                record.setRestoredVersion(response.path("restored_version").asText(null));
                record.setMessage(response.path("message").asText(""));
                deploymentRecordRepository.save(record);

                return objectMapper.convertValue(response, Map.class);
            }

            throw new RuntimeException("Empty response from deployment agent");

        } catch (Exception e) {
            throw new RuntimeException("Deployment failed: " + e.getMessage(), e);
        }
    }

    /**
     * Rollback deployment to a previous version.
     */
    public Map<String, Object> rollbackDeployment(User user, String owner, String repo, String targetVersion) {
        log.info("Rolling back deployment for {}/{} to {}", owner, repo, targetVersion);

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("owner", owner);
            body.put("repo", repo);
            if (targetVersion != null) {
                body.put("target_version", targetVersion);
            }

            JsonNode response = agentClient.post()
                    .uri("/api/deploy/rollback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null) {
                DeploymentRecord record = new DeploymentRecord();
                if (user != null) {
                    record.setUserId(user.getId());
                }
                record.setOwner(owner);
                record.setRepoName(repo);
                record.setCommitSha(response.path("commit_sha").asText(""));
                record.setVersion(response.path("version").asText(""));
                record.setEnvironment(response.path("environment").asText("production"));
                record.setImageTag(response.path("image_tag").asText(""));
                record.setStatus("rolled_back");
                record.setHealthCheckStatus("healthy");
                record.setRolledBack(true);
                record.setRestoredVersion(response.path("restored_version").asText(""));
                record.setMessage(response.path("message").asText("Rollback executed"));
                deploymentRecordRepository.save(record);

                return objectMapper.convertValue(response, Map.class);
            }

            throw new RuntimeException("Empty response from rollback agent");

        } catch (Exception e) {
            throw new RuntimeException("Rollback failed: " + e.getMessage(), e);
        }
    }

    /**
     * Execute arbitrary multi-agent workflow via the AI Orchestrator.
     */
    public Map<String, Object> executeWorkflow(String workflowName, Map<String, Object> payload) {
        log.info("Executing multi-agent workflow '{}'", workflowName);

        try {
            JsonNode response = agentClient.post()
                    .uri("/api/orchestrator/workflow")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("workflow", workflowName, "payload", payload != null ? payload : Map.of()))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null) {
                return objectMapper.convertValue(response, Map.class);
            }

            throw new RuntimeException("Empty response from orchestrator");

        } catch (Exception e) {
            throw new RuntimeException("Workflow execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate deployment plan.
     */
    public Map<String, Object> generateDeploymentPlan(User user, String owner, String repo, String branch) {
        log.info("Generating deployment plan for {}/{}", owner, repo);

        RepositoryContext context = repositoryContextBuilder.buildRepositoryContext(user, owner, repo, branch);

        try {
            JsonNode response = agentClient.post()
                    .uri("/api/deploy/plan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("context", objectMapper.convertValue(context, Map.class)))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null) {
                return objectMapper.convertValue(response, Map.class);
            }

            throw new RuntimeException("Empty response from deployment agent");

        } catch (Exception e) {
            throw new RuntimeException("Deployment planning failed: " + e.getMessage(), e);
        }
    }

    public List<PipelineRun> getPipelineHistory(Long userId) {
        return pipelineRunRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<PipelineRun> getPipelineHistory(Long userId, String owner, String repo) {
        return pipelineRunRepository.findByUserIdAndOwnerAndRepoNameOrderByCreatedAtDesc(userId, owner, repo);
    }

    public List<CodeReviewResult> getReviewHistory(Long userId) {
        return codeReviewResultRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<DeploymentRecord> getDeploymentHistory(Long userId, String owner, String repo) {
        if (owner != null && repo != null) {
            return deploymentRecordRepository.findByUserIdAndOwnerAndRepoNameOrderByCreatedAtDesc(userId, owner, repo);
        }
        return deploymentRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }
}

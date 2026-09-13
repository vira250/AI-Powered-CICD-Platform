package com.cicd.platform.controller;

import com.cicd.platform.model.User;
import com.cicd.platform.service.AgentOrchestratorService;
import com.cicd.platform.service.AuthenticatedUserResolver;
import com.cicd.platform.service.LogAnalysisPersistenceService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentOrchestratorService orchestratorService;
    private final AuthenticatedUserResolver authenticatedUserResolver;
    private final LogAnalysisPersistenceService logAnalysisPersistenceService;

    public AgentController(AgentOrchestratorService orchestratorService,
                           AuthenticatedUserResolver authenticatedUserResolver,
                           LogAnalysisPersistenceService logAnalysisPersistenceService) {
        this.orchestratorService = orchestratorService;
        this.authenticatedUserResolver = authenticatedUserResolver;
        this.logAnalysisPersistenceService = logAnalysisPersistenceService;
    }

    /**
     * Generate a CI/CD pipeline YAML for a repository.
     */
    @PostMapping("/pipeline/generate")
    public ResponseEntity<?> generatePipeline(HttpSession session, @RequestBody Map<String, String> body) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            String owner = body.get("owner");
            String repo = body.get("repo");
            String branch = body.getOrDefault("branch", null);

            if (owner == null || repo == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "owner and repo are required"));
            }

            Map<String, Object> result = orchestratorService.generatePipeline(user, owner, repo, branch);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Pipeline generation failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Multi-Agent Self-Healing Loop: Remediate a failed pipeline.
     */
    @PostMapping("/pipeline/remediate")
    public ResponseEntity<?> remediatePipeline(HttpSession session, @RequestBody Map<String, String> body) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            String owner = body.get("owner");
            String repo = body.get("repo");
            String branch = body.getOrDefault("branch", "main");
            String failedYaml = body.get("failed_yaml");
            String errorLogs = body.get("error_logs");

            if (owner == null || repo == null || failedYaml == null || errorLogs == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "owner, repo, failed_yaml, and error_logs are required"));
            }

            Map<String, Object> result = orchestratorService.remediatePipeline(user, owner, repo, branch, failedYaml, errorLogs);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Pipeline remediation failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Run a code review on a repository context.
     */
    @PostMapping("/review/analyze")
    public ResponseEntity<?> runCodeReview(HttpSession session, @RequestBody Map<String, String> body) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            String owner = body.get("owner");
            String repo = body.get("repo");
            String branch = body.getOrDefault("branch", null);

            if (owner == null || repo == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "owner and repo are required"));
            }

            Map<String, Object> result = orchestratorService.runCodeReview(user, owner, repo, branch);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Code review failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Run a code review on a Pull Request diff.
     */
    @PostMapping("/review/pull-request")
    public ResponseEntity<?> runPullRequestReview(HttpSession session, @RequestBody Map<String, Object> body) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            String owner = (String) body.get("owner");
            String repo = (String) body.get("repo");
            Number prNumberNum = (Number) body.get("pr_number");
            int prNumber = prNumberNum != null ? prNumberNum.intValue() : 0;
            String diff = (String) body.get("diff");
            String title = (String) body.get("title");
            String author = (String) body.get("author");

            if (owner == null || repo == null || diff == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "owner, repo, and diff are required"));
            }

            Map<String, Object> result = orchestratorService.runPullRequestReview(
                    user, owner, repo, prNumber, diff, title, author, user.getAccessToken()
            );
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("PR review failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Analyze pipeline logs for errors and root causes.
     */
    @PostMapping("/logs/analyze")
    public ResponseEntity<?> analyzeLogs(HttpSession session, @RequestBody Map<String, String> body) {
        try {
            authenticatedUserResolver.resolve(session);
            String logText = body.get("log_text");

            if (logText == null || logText.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "log_text is required"));
            }

            Map<String, Object> result = orchestratorService.analyzeLogs(logText);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Log analysis failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/logs/runs")
    public ResponseEntity<?> listWorkflowRuns(HttpSession session,
                                               @RequestParam String owner,
                                               @RequestParam String repo) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            return ResponseEntity.ok(orchestratorService.listWorkflowRuns(user, owner, repo));
        } catch (Exception e) {
            log.error("Unable to load GitHub Actions runs", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/logs/runs/{runId}/jobs")
    public ResponseEntity<?> listWorkflowJobs(HttpSession session,
                                               @RequestParam String owner,
                                               @RequestParam String repo,
                                               @PathVariable long runId) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            return ResponseEntity.ok(orchestratorService.listWorkflowJobs(user, owner, repo, runId));
        } catch (Exception e) {
            log.error("Unable to load GitHub Actions jobs", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logs/analyze-job")
    public ResponseEntity<?> analyzeWorkflowJobLogs(HttpSession session, @RequestBody Map<String, Object> body) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            String owner = (String) body.get("owner");
            String repo = (String) body.get("repo");
            Object rawJobId = body.get("job_id");
            Long jobId = null;
            if (rawJobId instanceof Number number) {
                jobId = number.longValue();
            } else if (rawJobId instanceof String value && !value.isBlank()) {
                try {
                    jobId = Long.valueOf(value);
                } catch (NumberFormatException ignored) {
                    // Handled by the validation response below.
                }
            }
            if (owner == null || repo == null || jobId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "owner, repo, and job_id are required"));
            }
            return ResponseEntity.ok(orchestratorService.analyzeWorkflowJobLogs(user, owner, repo, jobId));
        } catch (Exception e) {
            log.error("GitHub Actions log analysis failed", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/pipeline/{pipelineRunId}/log-analysis")
    public ResponseEntity<?> getPipelineLogAnalysis(HttpSession session, @PathVariable long pipelineRunId) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            return ResponseEntity.ok(orchestratorService.getPipelineLogAnalysis(user.getId(), pipelineRunId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            log.error("Unable to load automatic pipeline log analysis", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", exception.getMessage()));
        }
    }

    @GetMapping("/logs/analysis")
    public ResponseEntity<?> getLatestLogAnalysis(HttpSession session,
                                                  @RequestParam String owner,
                                                  @RequestParam String repo,
                                                  @RequestParam(name = "run_id") long runId,
                                                  @RequestParam(name = "job_id") long jobId) {
        try {
            authenticatedUserResolver.resolve(session);
            return ResponseEntity.ok(logAnalysisPersistenceService.findLatest(owner, repo, runId, jobId)
                    .orElseGet(Map::of));
        } catch (Exception e) {
            log.error("Unable to load saved GitHub Actions log analysis", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Generate a deployment plan for a repository.
     */
    @PostMapping("/deploy/plan")
    public ResponseEntity<?> generateDeploymentPlan(HttpSession session, @RequestBody Map<String, String> body) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            String owner = body.get("owner");
            String repo = body.get("repo");
            String branch = body.getOrDefault("branch", null);

            if (owner == null || repo == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "owner and repo are required"));
            }

            Map<String, Object> result = orchestratorService.generateDeploymentPlan(user, owner, repo, branch);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Deployment planning failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Execute production deployment lifecycle (Docker -> Deploy -> Health Check -> Rollback on failure).
     */
    @PostMapping("/deploy/execute")
    public ResponseEntity<?> executeDeployment(HttpSession session, @RequestBody Map<String, Object> body) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            String owner = (String) body.get("owner");
            String repo = (String) body.get("repo");
            String commitSha = (String) body.getOrDefault("commit_sha", "HEAD");
            String environment = (String) body.getOrDefault("environment", "production");
            String imageTag = (String) body.get("image_tag");
            boolean simulateFailure = Boolean.TRUE.equals(body.get("simulate_health_failure"));

            if (owner == null || repo == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "owner and repo are required"));
            }

            Map<String, Object> result = orchestratorService.executeDeployment(
                    user, owner, repo, commitSha, environment, imageTag, simulateFailure
            );
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Deployment execution failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Rollback deployment to a previous version.
     */
    @PostMapping("/deploy/rollback")
    public ResponseEntity<?> rollbackDeployment(HttpSession session, @RequestBody Map<String, String> body) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            String owner = body.get("owner");
            String repo = body.get("repo");
            String targetVersion = body.get("target_version");

            if (owner == null || repo == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "owner and repo are required"));
            }

            Map<String, Object> result = orchestratorService.rollbackDeployment(user, owner, repo, targetVersion);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Rollback failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get deployment history.
     */
    @GetMapping("/deploy/history")
    public ResponseEntity<?> getDeploymentHistory(HttpSession session,
                                                  @RequestParam(required = false) String owner,
                                                  @RequestParam(required = false) String repo) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            return ResponseEntity.ok(orchestratorService.getDeploymentHistory(user.getId(), owner, repo));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Execute arbitrary multi-agent workflow.
     */
    @PostMapping("/orchestrator/workflow")
    public ResponseEntity<?> executeWorkflow(HttpSession session, @RequestBody Map<String, Object> body) {
        try {
            authenticatedUserResolver.resolve(session);
            String workflow = (String) body.get("workflow");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) body.getOrDefault("payload", Map.of());

            if (workflow == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "workflow name is required"));
            }

            Map<String, Object> result = orchestratorService.executeWorkflow(workflow, payload);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get pipeline generation history for the authenticated user.
     */
    @GetMapping("/pipeline/history")
    public ResponseEntity<?> getPipelineHistory(HttpSession session,
                                                @RequestParam(required = false) String owner,
                                                @RequestParam(required = false) String repo) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            if (owner != null && repo != null) {
                return ResponseEntity.ok(orchestratorService.getPipelineHistory(user.getId(), owner, repo));
            }
            return ResponseEntity.ok(orchestratorService.getPipelineHistory(user.getId()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get code review history for the authenticated user.
     */
    @GetMapping("/review/history")
    public ResponseEntity<?> getReviewHistory(HttpSession session) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            return ResponseEntity.ok(orchestratorService.getReviewHistory(user.getId()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}

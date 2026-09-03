package com.aicicd.platform.pipeline;

import com.aicicd.platform.github.GitHubApiClient;
import com.aicicd.platform.github.GitHubAppService;
import com.aicicd.platform.repo.ConnectedRepository;
import com.aicicd.platform.repo.ConnectedRepositoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Pipeline monitoring endpoints. */
@RestController
@RequestMapping("/api")
public class PipelineController {

    private final PipelineRepository pipelines;
    private final ConnectedRepositoryRepository repos;
    private final GitHubAppService appService;
    private final GitHubApiClient github;

    public PipelineController(PipelineRepository pipelines,
                              ConnectedRepositoryRepository repos,
                              GitHubAppService appService,
                              GitHubApiClient github) {
        this.pipelines = pipelines;
        this.repos = repos;
        this.appService = appService;
        this.github = github;
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
}

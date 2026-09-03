package com.aicicd.platform.repo;

import com.aicicd.platform.github.GitHubApiClient;
import com.aicicd.platform.github.GitHubAppService;
import com.aicicd.platform.orchestrator.OrchestratorClient;
import com.aicicd.platform.pipeline.PipelineEntity;
import com.aicicd.platform.pipeline.PipelineRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Repository management + the "Generate Pipeline" flow:
 *  save repo to DB -> fetch file list -> AI Orchestrator (pipeline
 *  generation agent) -> persist YAML -> commit to GitHub -> GitHub Actions.
 */
@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final ConnectedRepositoryRepository repos;
    private final PipelineRepository pipelines;
    private final GitHubAppService appService;
    private final GitHubApiClient github;
    private final OrchestratorClient orchestrator;
    private final ObjectMapper mapper = new ObjectMapper();

    public RepoController(ConnectedRepositoryRepository repos,
                          PipelineRepository pipelines,
                          GitHubAppService appService,
                          GitHubApiClient github,
                          OrchestratorClient orchestrator) {
        this.repos = repos;
        this.pipelines = pipelines;
        this.appService = appService;
        this.github = github;
        this.orchestrator = orchestrator;
    }

    // ---- repositories the GitHub App installation can see ----------------
    @GetMapping("/available")
    public List<Map<String, Object>> available(@RequestParam long installationId) {
        String token = appService.installationToken(installationId);
        return github.listInstallationRepos(token);
    }

    // ---- auto-discover all installations of this GitHub App -------------
    @GetMapping("/installations")
    public List<Map<String, Object>> listInstallations() {
        try {
            return appService.listInstallations();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

    // ---- auto-discover all repositories across all installations --------
    @GetMapping("/auto-available")
    public List<Map<String, Object>> autoAvailable() {
        List<Map<String, Object>> allRepos = new java.util.ArrayList<>();
        try {
            List<Map<String, Object>> installations = appService.listInstallations();
            if (installations != null) {
                for (Map<String, Object> inst : installations) {
                    if (inst.get("id") instanceof Number num) {
                        long instId = num.longValue();
                        String token = appService.installationToken(instId);
                        List<Map<String, Object>> reposList = github.listInstallationRepos(token);
                        for (Map<String, Object> r : reposList) {
                            java.util.Map<String, Object> copy = new java.util.HashMap<>(r);
                            copy.put("installationId", instId);
                            allRepos.add(copy);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return allRepos;
    }

    // ---- repos already saved in our DB -----------------------------------
    @GetMapping
    public List<ConnectedRepository> connected() {
        return repos.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConnectedRepository> getById(@PathVariable Long id) {
        return repos.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional("repoTransactionManager")
    public ResponseEntity<?> disconnect(@PathVariable Long id) {
        return repos.findById(id).map(repo -> {
            pipelines.deleteByRepositoryId(id);
            repos.delete(repo);
            return ResponseEntity.ok(Map.of("message", "Repository disconnected successfully", "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/connect")
    @Transactional("repoTransactionManager")
    public ConnectedRepository connect(@RequestBody Map<String, Object> body) {
        String fullName = (String) body.get("fullName");
        return repos.findByFullName(fullName).orElseGet(() -> {
            ConnectedRepository r = new ConnectedRepository();
            r.setInstallationId(((Number) body.get("installationId")).longValue());
            r.setOwner((String) body.get("owner"));
            r.setName((String) body.get("name"));
            r.setFullName(fullName);
            r.setDefaultBranch((String) body.getOrDefault("defaultBranch", "main"));
            if (body.get("isPrivate") instanceof Boolean b) {
                r.setIsPrivate(b);
            } else if (body.get("private") instanceof Boolean b) {
                r.setIsPrivate(b);
            }
            return repos.save(r);
        });
    }

    @GetMapping("/{id}/context")
    public ResponseEntity<?> getRepoContextById(@PathVariable Long id,
                                                @RequestParam(required = false) String branch) {
        ConnectedRepository repo = repos.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("repo not connected"));
        String token = appService.installationToken(repo.getInstallationId());
        String targetBranch = (branch != null && !branch.isBlank()) ? branch : repo.getDefaultBranch();
        return ResponseEntity.ok(github.getRepositoryContext(token, repo.getFullName(), targetBranch));
    }

    @GetMapping("/{owner}/{name}/context")
    public ResponseEntity<?> getRepoContextByOwnerAndName(@PathVariable String owner,
                                                          @PathVariable String name,
                                                          @RequestParam(required = false) String branch) {
        String fullName = owner + "/" + name;
        ConnectedRepository repo = repos.findByFullName(fullName)
                .orElseThrow(() -> new IllegalArgumentException("repo not connected"));
        String token = appService.installationToken(repo.getInstallationId());
        String targetBranch = (branch != null && !branch.isBlank()) ? branch : repo.getDefaultBranch();
        return ResponseEntity.ok(github.getRepositoryContext(token, repo.getFullName(), targetBranch));
    }

    // ---- Generate Pipeline button ----------------------------------------
    @PostMapping("/{id}/generate-pipeline")
    @Transactional("repoTransactionManager")
    public ResponseEntity<?> generatePipeline(@PathVariable Long id) throws Exception {
        ConnectedRepository repo = repos.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("repo not connected"));
        String token = appService.installationToken(repo.getInstallationId());

        // 1. Fetch complete repository context + file list -> agents service
        Map<String, Object> repoContext = github.getRepositoryContext(token, repo.getFullName(), repo.getDefaultBranch());
        List<String> files = github.listFiles(token, repo.getFullName(), repo.getDefaultBranch());

        Map<String, Object> orchPayload = new java.util.HashMap<>();
        orchPayload.put("files", files);
        orchPayload.put("repo_context", repoContext);

        Map<String, Object> result = orchestrator.orchestrate("generate_pipeline", orchPayload);

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        if (output == null || output.get("workflow_yaml") == null) {
            return ResponseEntity.badRequest().body(result);
        }

        // 2. Save pipeline to the DB
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setRepository(repo);
        pipeline.setWorkflowPath((String) output.get("workflow_path"));
        pipeline.setWorkflowYaml((String) output.get("workflow_yaml"));
        pipeline.setTemplateUsed((String) output.get("template_used"));
        pipeline.setStackJson(mapper.writeValueAsString(output.get("stack")));

        // Save credits and tokens
        if (output.get("credits_used") instanceof Number n) {
            pipeline.setCreditsUsed(n.doubleValue());
        }
        if (output.get("total_tokens") instanceof Number n) {
            pipeline.setTotalTokens(n.intValue());
        }

        pipeline.setStatus(PipelineEntity.Status.GENERATED);
        pipelines.save(pipeline);

        // 3. Push the workflow file to GitHub -> GitHub Actions picks it up
        github.commitFile(token, repo.getFullName(), pipeline.getWorkflowPath(),
                pipeline.getWorkflowYaml(),
                "chore: add AI-generated CI/CD pipeline [ai-cicd-platform]",
                repo.getDefaultBranch());
        pipeline.setStatus(PipelineEntity.Status.PUSHED);
        pipeline.setPushedAt(Instant.now());
        pipelines.save(pipeline);

        Map<String, Object> respMap = new java.util.HashMap<>();
        respMap.put("pipelineId", pipeline.getId());
        respMap.put("status", pipeline.getStatus().name());
        respMap.put("stack", output.get("stack"));
        respMap.put("templateUsed", pipeline.getTemplateUsed());
        respMap.put("workflowPath", pipeline.getWorkflowPath());
        respMap.put("creditsUsed", pipeline.getCreditsUsed());
        respMap.put("totalTokens", pipeline.getTotalTokens());
        return ResponseEntity.ok(respMap);
    }
}

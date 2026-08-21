package com.aicicd.platform.repo;

import com.aicicd.platform.github.GitHubApiClient;
import com.aicicd.platform.github.GitHubAppService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Git Push API endpoint: enables writing and pushing generated workflows
 * or commits directly to the GitHub repository.
 */
@RestController
@RequestMapping("/api/git")
public class GitController {

    private final ConnectedRepositoryRepository repos;
    private final GitHubAppService appService;
    private final GitHubApiClient github;

    public GitController(ConnectedRepositoryRepository repos,
                         GitHubAppService appService,
                         GitHubApiClient github) {
        this.repos = repos;
        this.appService = appService;
        this.github = github;
    }

    @PostMapping("/push")
    public ResponseEntity<?> pushFile(@RequestBody Map<String, Object> body) {
        String owner = (String) body.get("owner");
        String repoName = (String) body.get("repo");
        String fullName = (owner != null && repoName != null) ? (owner + "/" + repoName) : (String) body.get("fullName");
        if (fullName == null || fullName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "owner/repo or fullName is required"));
        }

        ConnectedRepository repo = repos.findByFullName(fullName)
                .or(() -> repos.findAll().stream()
                        .filter(r -> r.getFullName() != null && r.getFullName().equalsIgnoreCase(fullName))
                        .findFirst())
                .orElseThrow(() -> new IllegalArgumentException("repository not connected: " + fullName));

        String token = appService.installationToken(repo.getInstallationId());
        String path = (String) body.getOrDefault("path", ".github/workflows/ai-ci-cd.yml");
        String content = (String) body.get("content");
        String message = (String) body.getOrDefault("message", "chore: add CI/CD pipeline [ai-cicd-platform]");
        String branch = (String) body.getOrDefault("branch", repo.getDefaultBranch());

        if (content == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }

        github.commitFile(token, repo.getFullName(), path, content, message, branch);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "repository", repo.getFullName(),
                "path", path,
                "branch", branch,
                "message", message
        ));
    }
}

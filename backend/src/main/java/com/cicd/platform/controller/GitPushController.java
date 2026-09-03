package com.cicd.platform.controller;

import com.cicd.platform.model.User;
import com.cicd.platform.service.AuthenticatedUserResolver;
import com.cicd.platform.service.GitHubService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/git")
public class GitPushController {

    private static final Logger log = LoggerFactory.getLogger(GitPushController.class);

    private final GitHubService githubService;
    private final AuthenticatedUserResolver authenticatedUserResolver;

    public GitPushController(GitHubService githubService, AuthenticatedUserResolver authenticatedUserResolver) {
        this.githubService = githubService;
        this.authenticatedUserResolver = authenticatedUserResolver;
    }

    /**
     * Push (create or update) a file to a GitHub repository.
     *
     * Request body:
     * {
     *   "owner": "username",
     *   "repo": "repo-name",
     *   "path": "src/hello.txt",
     *   "content": "Hello World!",
     *   "message": "Add hello.txt",
     *   "branch": "main" (optional)
     * }
     */
    @PostMapping("/push")
    public ResponseEntity<?> pushFile(HttpSession session, @RequestBody Map<String, String> body) {
        String owner = body.get("owner");
        String repo = body.get("repo");
        String path = body.get("path");
        String content = body.get("content");
        String message = body.get("message");
        String branch = body.getOrDefault("branch", null);

        User user = authenticatedUserResolver.resolve(session);

        if (owner == null || repo == null || path == null || content == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required fields: owner, repo, path, content"));
        }

        try {
            Map<String, Object> result = githubService.pushFile(user, owner, repo, path, content, message, branch);
            log.info("Successfully pushed file {} to {}/{}", path, owner, repo);
            return ResponseEntity.ok(result);
        } catch (com.cicd.platform.exception.GithubApiException e) {
            log.error("GitHub API error during push to {}/{}/{}: {}", owner, repo, path, e.getMessage());
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("error", "GitHub API Error (" + e.getStatus().value() + "): " + e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to push file to {}/{}/{}", owner, repo, path, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to push file: " + e.getMessage()));
        }
    }
}

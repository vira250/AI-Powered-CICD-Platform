package com.cicd.platform.controller;

import com.cicd.platform.dto.GithubRepositoryDTO;
import com.cicd.platform.model.User;
import com.cicd.platform.service.AuthenticatedUserResolver;
import com.cicd.platform.service.GitHubService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/github")
public class GithubController {

    private final GitHubService githubService;
    private final AuthenticatedUserResolver authenticatedUserResolver;

    public GithubController(GitHubService githubService, AuthenticatedUserResolver authenticatedUserResolver) {
        this.githubService = githubService;
        this.authenticatedUserResolver = authenticatedUserResolver;
    }

    @GetMapping("/repos")
    public ResponseEntity<List<GithubRepositoryDTO>> getRepositories(
            HttpSession session,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "per_page", required = false) Integer perPage) {

        User user = authenticatedUserResolver.resolve(session);
        return ResponseEntity.ok(githubService.listRepositories(user, page, perPage));
    }

    @GetMapping("/selected-repository")
    public ResponseEntity<?> getSelectedRepository(HttpSession session) {
        User user = authenticatedUserResolver.resolve(session);
        return ResponseEntity.ok(readSelectedRepository(session, user));
    }

    @PostMapping("/selected-repository")
    public ResponseEntity<?> selectRepository(
            HttpSession session,
            @RequestBody GithubRepositoryDTO repository) {

        User user = authenticatedUserResolver.resolve(session);
        storeSelectedRepository(session, repository, user);
        return ResponseEntity.ok(repository);
    }

    private Map<String, Object> readSelectedRepository(HttpSession session, User user) {
        Map<String, Object> repository = new HashMap<>();
        repository.put("id", session.getAttribute("selectedGithubRepositoryId"));
        repository.put("name", session.getAttribute("selectedGithubRepositoryName"));
        repository.put("full_name", session.getAttribute("selectedGithubRepositoryFullName"));
        repository.put("default_branch", session.getAttribute("selectedGithubRepositoryDefaultBranch"));
        repository.put("owner", session.getAttribute("selectedGithubRepositoryOwner"));

        if (repository.values().stream().allMatch(value -> value == null)) {
            repository.put("message", "No repository selected");
            repository.put("userId", user.getId());
        }

        return repository;
    }

    private void storeSelectedRepository(HttpSession session, GithubRepositoryDTO repository, User user) {
        session.setAttribute("selectedGithubRepositoryId", repository.id());
        session.setAttribute("selectedGithubRepositoryName", repository.name());
        session.setAttribute("selectedGithubRepositoryFullName", repository.fullName());
        session.setAttribute("selectedGithubRepositoryDefaultBranch", repository.defaultBranch());
        session.setAttribute("selectedGithubRepositoryOwner", repository.owner() != null ? repository.owner().login() : null);
        session.setAttribute("selectedGithubUserId", user.getId());
    }
}
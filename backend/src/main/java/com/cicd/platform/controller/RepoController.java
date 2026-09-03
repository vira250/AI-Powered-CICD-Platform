package com.cicd.platform.controller;

import com.cicd.platform.model.ImportedRepo;
import com.cicd.platform.model.User;
import com.cicd.platform.repository.ImportedRepoRepository;
import com.cicd.platform.repository.UserRepository;
import com.cicd.platform.service.AuthenticatedUserResolver;
import com.cicd.platform.service.GitHubService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private static final Logger log = LoggerFactory.getLogger(RepoController.class);

    private final GitHubService githubService;
    private final UserRepository userRepository;
    private final ImportedRepoRepository importedRepoRepository;
    private final AuthenticatedUserResolver authenticatedUserResolver;
    private final com.cicd.platform.service.RepoSyncService repoSyncService;
    private final com.cicd.platform.repository.RepoFileRepository repoFileRepository;
    private final com.cicd.platform.service.RepositoryContextBuilder repositoryContextBuilder;

    public RepoController(GitHubService githubService, UserRepository userRepository,
                           ImportedRepoRepository importedRepoRepository,
                           AuthenticatedUserResolver authenticatedUserResolver,
                           com.cicd.platform.service.RepoSyncService repoSyncService,
                           com.cicd.platform.repository.RepoFileRepository repoFileRepository,
                           com.cicd.platform.service.RepositoryContextBuilder repositoryContextBuilder) {
        this.githubService = githubService;
        this.userRepository = userRepository;
        this.importedRepoRepository = importedRepoRepository;
        this.authenticatedUserResolver = authenticatedUserResolver;
        this.repoSyncService = repoSyncService;
        this.repoFileRepository = repoFileRepository;
        this.repositoryContextBuilder = repositoryContextBuilder;
    }

    /**
     * List all GitHub repositories for the authenticated user.
     */
    @GetMapping
    public ResponseEntity<?> listRepos(
            HttpSession session,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int perPage,
            @RequestParam(defaultValue = "updated") String sort) {

        try {
            User user = authenticatedUserResolver.resolve(session);
            List<Map<String, Object>> repos = githubService.getUserRepos(user, page, perPage, sort);

            // Mark repos that are already imported
            Long userId = user.getId();
            for (Map<String, Object> repo : repos) {
                Long repoId = ((Number) repo.get("id")).longValue();
                repo.put("imported", importedRepoRepository.existsByUserIdAndGithubRepoId(userId, repoId));
            }

            return ResponseEntity.ok(repos);
        } catch (Exception e) {
            log.error("Failed to list repos", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch repositories"));
        }
    }

    /**
     * Get details of a specific repository.
     */
    @GetMapping("/{owner}/{repo}")
    public ResponseEntity<?> getRepoDetails(
            HttpSession session,
            @PathVariable String owner,
            @PathVariable String repo) {

        try {
            User user = authenticatedUserResolver.resolve(session);
            Map<String, Object> details = githubService.getRepoDetails(user, owner, repo);

            Long userId = user.getId();
            Long repoId = ((Number) details.get("id")).longValue();
            details.put("imported", importedRepoRepository.existsByUserIdAndGithubRepoId(userId, repoId));

            return ResponseEntity.ok(details);
        } catch (Exception e) {
            log.error("Failed to get repo details for {}/{}", owner, repo, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Repository not found"));
        }
    }

    /**
     * Get the recursive folder structure for a repository.
     * Serves from PostgreSQL database if imported, or live from GitHub API.
     */
    @GetMapping("/{owner}/{repo}/structure")
    public ResponseEntity<?> getRepoStructure(
            HttpSession session,
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam(required = false) String branch) {

        try {
            User user = authenticatedUserResolver.resolve(session);
            String fullName = owner + "/" + repo;
            var importedOpt = importedRepoRepository.findByUserId(user.getId()).stream()
                    .filter(r -> fullName.equalsIgnoreCase(r.getFullName()) || repo.equalsIgnoreCase(r.getName()))
                    .findFirst();

            if (importedOpt.isPresent()) {
                var postgresTree = repoSyncService.buildStructureFromDatabase(importedOpt.get().getId(), branch);
                if (postgresTree != null) {
                    log.info("Serving repository tree from PostgreSQL for {}", fullName);
                    return ResponseEntity.ok(postgresTree);
                }
            }

            return ResponseEntity.ok(githubService.getRepoStructure(user, owner, repo, branch));
        } catch (Exception e) {
            log.error("Failed to get repo structure for {}/{}", owner, repo, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Repository structure not found"));
        }
    }

    /**
     * Get single file content from repository.
     * Serves from PostgreSQL database if cached, or live from GitHub API.
     */
    @GetMapping("/{owner}/{repo}/file")
    public ResponseEntity<?> getFileContent(
            HttpSession session,
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam String path,
            @RequestParam(required = false) String branch) {

        try {
            User user = authenticatedUserResolver.resolve(session);
            String fullName = owner + "/" + repo;
            var importedOpt = importedRepoRepository.findByUserId(user.getId()).stream()
                    .filter(r -> fullName.equalsIgnoreCase(r.getFullName()) || repo.equalsIgnoreCase(r.getName()))
                    .findFirst();

            if (importedOpt.isPresent()) {
                var fileOpt = repoFileRepository.findByImportedRepoIdAndPath(importedOpt.get().getId(), path);
                if (fileOpt.isPresent() && fileOpt.get().getContent() != null) {
                    log.info("Serving file content from PostgreSQL for {} path {}", fullName, path);
                    var f = fileOpt.get();
                    Map<String, Object> result = new java.util.LinkedHashMap<>();
                    result.put("name", f.getName());
                    result.put("path", f.getPath());
                    result.put("sha", f.getSha() != null ? f.getSha() : "");
                    result.put("size", f.getSize() != null ? f.getSize() : 0);
                    result.put("html_url", "https://github.com/" + fullName + "/blob/" + (branch != null ? branch : "main") + "/" + path);
                    result.put("content", f.getContent());
                    result.put("encoding", "utf-8");
                    result.put("branch", branch != null ? branch : "main");
                    result.put("source", "postgresql");
                    return ResponseEntity.ok(result);
                }
            }

            return ResponseEntity.ok(githubService.getFileContent(user, owner, repo, path, branch));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "File content not found: " + e.getMessage()));
        }
    }

    /**
     * Get complete RepositoryContext (commit SHA, complete directory structure, metadata, and text file contents)
     * constructed from GitHub for the Pipeline Generation Agent.
     */
    @GetMapping("/{owner}/{repo}/context")
    public ResponseEntity<?> getRepositoryContext(
            HttpSession session,
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestParam(required = false) String branch) {

        try {
            User user = authenticatedUserResolver.resolve(session);
            var context = repositoryContextBuilder.buildRepositoryContext(user, owner, repo, branch);
            return ResponseEntity.ok(context);
        } catch (Exception e) {
            log.error("Failed to build RepositoryContext for {}/{}", owner, repo, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to build repository context: " + e.getMessage()));
        }
    }

    /**
     * Import a repository to our platform and persist its folder structure + file contents into PostgreSQL.
     */
    @PostMapping("/import")
    public ResponseEntity<?> importRepo(HttpSession session, @RequestBody Map<String, Object> body) {
        try {
            User user = authenticatedUserResolver.resolve(session);
            Long userId = user.getId();

            Object idObj = body.get("id");
            if (idObj == null) {
                idObj = body.get("githubRepoId");
            }
            if (idObj == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Repository ID is required"));
            }
            Long githubRepoId = ((Number) idObj).longValue();

            // Check if already imported
            if (importedRepoRepository.existsByUserIdAndGithubRepoId(userId, githubRepoId)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Repository already imported"));
            }

            String owner = extractString(body.get("owner"), extractString(body.get("owner_login"), extractString(body.get("ownerLogin"), "unknown")));
            String name = extractString(body.get("name"), "unnamed-repo");
            String fullName = extractString(body.get("full_name"), extractString(body.get("fullName"), owner + "/" + name));
            String defaultBranch = extractString(body.get("default_branch"), extractString(body.get("defaultBranch"), "main"));
            String htmlUrl = extractString(body.get("html_url"), extractString(body.get("htmlUrl"), "https://github.com/" + fullName));
            String cloneUrl = extractString(body.get("clone_url"), extractString(body.get("cloneUrl"), htmlUrl + ".git"));
            
            Object privateValue = body.containsKey("private") ? body.get("private") : body.get("privateRepository");

            ImportedRepo imported = new ImportedRepo();
            imported.setUserId(userId);
            imported.setGithubRepoId(githubRepoId);
            imported.setFullName(fullName);
            imported.setName(name);
            imported.setOwner(owner);
            imported.setDescription(extractString(body.get("description"), ""));
            imported.setLanguage(extractString(body.get("language"), ""));
            imported.setDefaultBranch(defaultBranch);
            imported.setHtmlUrl(htmlUrl);
            imported.setCloneUrl(cloneUrl);
            imported.setPrivate(privateValue instanceof Boolean ? (Boolean) privateValue : false);
            imported.setStars(body.containsKey("stargazers_count")
                    ? ((Number) body.get("stargazers_count")).intValue() : 0);

            importedRepoRepository.save(imported);
            log.info("User {} imported repo {}", userId, imported.getFullName());

            // Synchronize and persist folder structure & files into PostgreSQL database
            repoSyncService.syncRepoStructureAndFiles(user, imported);

            return ResponseEntity.ok(Map.of(
                    "message", "Repository imported successfully and synced to PostgreSQL",
                    "repo", imported.getFullName()
            ));
        } catch (Exception e) {
            log.error("Failed to import repo", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to import repository: " + e.getMessage()));
        }
    }

    /**
     * List all imported repositories for the authenticated user.
     */
    @GetMapping("/imported")
    public ResponseEntity<?> listImportedRepos(HttpSession session) {
        User user = authenticatedUserResolver.resolve(session);
        Long userId = user.getId();
        List<ImportedRepo> repos = importedRepoRepository.findByUserId(userId);
        return ResponseEntity.ok(repos);
    }

    /**
     * Remove an imported repository and delete its files from PostgreSQL.
     */
    @DeleteMapping("/imported/{id}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> removeImportedRepo(HttpSession session, @PathVariable Long id) {
        User user = authenticatedUserResolver.resolve(session);
        Long userId = user.getId();

        return importedRepoRepository.findById(id)
                .filter(repo -> repo.getUserId().equals(userId))
                .map(repo -> {
                    repoFileRepository.deleteByImportedRepoId(repo.getId());
                    importedRepoRepository.delete(repo);
                    return ResponseEntity.ok(Map.of("message", "Repository removed from PostgreSQL"));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Imported repository not found")));
    }

    @SuppressWarnings("unchecked")
    private String extractString(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("login")) {
                return map.get("login").toString();
            }
        }
        String str = value.toString();
        return str.isBlank() ? defaultValue : str;
    }
}

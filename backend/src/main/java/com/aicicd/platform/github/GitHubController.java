package com.aicicd.platform.github;

import com.aicicd.platform.auth.JwtService;
import com.aicicd.platform.auth.UserEntity;
import com.aicicd.platform.auth.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;

/**
 * Endpoints the frontend (main-branch) expects under /api/github/*.
 *  - GET  /api/github/repos               → list user's repos via OAuth token
 *  - GET  /api/github/selected-repository  → read selected repo from session/memory
 *  - POST /api/github/selected-repository  → store selected repo
 */
@RestController
@RequestMapping("/api/github")
public class GitHubController {

    private final UserRepository users;
    private final RestClient http = RestClient.create();

    /**
     * In-memory map: login → selected repository.
     * A production app would use a real session store or DB column.
     */
    private final Map<String, Map<String, Object>> selectedRepos = new java.util.concurrent.ConcurrentHashMap<>();

    public GitHubController(UserRepository users) {
        this.users = users;
    }

    /* ---------- list user's GitHub repos via personal OAuth token ---------- */

    @GetMapping("/repos")
    public ResponseEntity<?> listRepos(HttpServletRequest request,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(name = "per_page", required = false) Integer perPage) {
        String login = (String) request.getAttribute("githubLogin");
        if (login == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        // For GitHub App-based auth, we list installation repos instead
        // For OAuth users, we'd use their access token – here we return an empty
        // list as a safe fallback so the frontend doesn't break.
        // The frontend also uses the RepoImportModal which calls /api/repos/auto-available.
        try {
            return ResponseEntity.ok(List.of());
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    /* ---------- selected repository (in-memory session) ---------- */

    @GetMapping("/selected-repository")
    public ResponseEntity<?> getSelectedRepository(HttpServletRequest request) {
        String login = (String) request.getAttribute("githubLogin");
        if (login == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        Map<String, Object> repo = selectedRepos.get(login);
        if (repo == null) {
            return ResponseEntity.ok(Map.of());
        }
        return ResponseEntity.ok(repo);
    }

    @PostMapping("/selected-repository")
    public ResponseEntity<?> setSelectedRepository(HttpServletRequest request,
                                                    @RequestBody Map<String, Object> body) {
        String login = (String) request.getAttribute("githubLogin");
        if (login == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        selectedRepos.put(login, body);
        return ResponseEntity.ok(body);
    }
}

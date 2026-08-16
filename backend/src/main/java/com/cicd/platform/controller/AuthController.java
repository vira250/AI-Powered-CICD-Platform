package com.cicd.platform.controller;

import com.cicd.platform.model.User;
import com.cicd.platform.repository.UserRepository;
import com.cicd.platform.service.GitHubService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final GitHubService githubService;
    private final UserRepository userRepository;

    @Value("${github.client.id}")
    private String clientId;

    @Value("${github.oauth.scope}")
    private String oauthScope;

    @Value("${github.redirect.uri}")
    private String redirectUri;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    public AuthController(GitHubService githubService, UserRepository userRepository) {
        this.githubService = githubService;
        this.userRepository = userRepository;
    }

    /**
     * Step 1: Redirect user to GitHub App install & authorize page.
     * This uses the combined install + OAuth flow for GitHub Apps.
     */
    @GetMapping("/github")
    public ResponseEntity<Void> redirectToGitHub() {
        // For GitHub Apps, the authorization URL is:
        // https://github.com/apps/<app-slug>/installations/new
        // But for the combined OAuth flow, we use:
        String githubAuthUrl = String.format(
                "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=%s",
                clientId,
                URLEncoder.encode(redirectUri, StandardCharsets.UTF_8),
                URLEncoder.encode(oauthScope, StandardCharsets.UTF_8)
        );

        log.info("Redirecting to GitHub OAuth: {}", githubAuthUrl);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(githubAuthUrl))
                .build();
    }

    /**
     * Step 2: GitHub redirects back here with a code.
     * Exchange it for an access token, fetch user profile, store in DB + session.
     */
    @GetMapping("/github/callback")
    public ResponseEntity<Void> githubCallback(
            @RequestParam("code") String code,
            @RequestParam(value = "installation_id", required = false) Long installationId,
            @RequestParam(value = "setup_action", required = false) String setupAction,
            HttpSession session) {
        try {
            // Exchange code for access token
            String accessToken = githubService.exchangeCodeForToken(code);
            log.info("Successfully obtained GitHub access token");

            // Fetch user profile
            Map<String, Object> profile = githubService.getUserProfile(accessToken);
            Long githubId = ((Number) profile.get("id")).longValue();
            String username = (String) profile.get("login");

            // Upsert user in database
            User user = userRepository.findByGithubId(githubId)
                    .orElse(new User());

            user.setGithubId(githubId);
            user.setUsername(username);
            user.setName((String) profile.get("name"));
            user.setEmail((String) profile.get("email"));
            user.setAvatarUrl((String) profile.get("avatar_url"));
            user.setProfileUrl((String) profile.get("html_url"));
            user.setAccessToken(accessToken);
            user.setLastLoginAt(LocalDateTime.now());

            if (installationId != null) {
                user.setInstallationId(installationId);
                log.info("Captured installation ID {} for user {}", installationId, username);
            }

            String sessionToken = java.util.UUID.randomUUID().toString();
            user.setSessionToken(sessionToken);

            userRepository.save(user);
            log.info("User {} logged in successfully with session token", username);

            // Store user ID in session as well
            session.setAttribute("userId", user.getId());

            // Redirect to frontend dashboard with token parameter
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendUrl + "/dashboard?token=" + sessionToken))
                    .build();

        } catch (Exception e) {
            log.error("GitHub OAuth callback failed", e);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendUrl + "?error=auth_failed"))
                    .build();
        }
    }

    /**
     * Get the currently authenticated user's info.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(jakarta.servlet.http.HttpServletRequest request, HttpSession session) {
        Long userId = (Long) request.getAttribute("authenticatedUserId");
        if (userId == null) {
            userId = (Long) session.getAttribute("userId");
        }
        if (userId == null && org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() != null) {
            try {
                String name = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
                userId = Long.parseLong(name);
            } catch (Exception ignored) {}
        }

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }

        return userRepository.findById(userId)
                .map(user -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("id", user.getId());
                    response.put("githubId", user.getGithubId());
                    response.put("username", user.getUsername());
                    response.put("name", user.getName());
                    response.put("email", user.getEmail());
                    response.put("avatarUrl", user.getAvatarUrl());
                    response.put("profileUrl", user.getProfileUrl());
                    response.put("token", user.getSessionToken());
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "User not found")));
    }

    /**
     * Logout — clear session and invalidate session token in database.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(jakarta.servlet.http.HttpServletRequest request, HttpSession session) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if (!token.isEmpty()) {
                userRepository.findBySessionToken(token).ifPresent(user -> {
                    user.setSessionToken(null);
                    userRepository.save(user);
                });
            }
        }

        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}

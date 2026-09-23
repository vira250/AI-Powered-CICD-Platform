package com.cicd.platform.controller;

import com.cicd.platform.dto.GoogleTokenRequest;
import com.cicd.platform.dto.LoginRequest;
import com.cicd.platform.dto.SignUpRequest;
import com.cicd.platform.model.User;
import com.cicd.platform.repository.UserRepository;
import com.cicd.platform.service.GitHubService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

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
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final WebClient webClient = WebClient.create();

    @Value("${github.client.id}")
    private String clientId;

    @Value("${github.oauth.scope}")
    private String oauthScope;

    @Value("${github.redirect.uri}")
    private String redirectUri;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Value("${google.client.id:}")
    private String googleClientId;

    public AuthController(GitHubService githubService, UserRepository userRepository) {
        this.githubService = githubService;
        this.userRepository = userRepository;
    }

    // ==================== Email/Password Auth ====================

    /**
     * Sign up with email and password.
     */
    @PostMapping("/signup")
    public ResponseEntity<?> signUp(@RequestBody SignUpRequest request, HttpSession session) {
        try {
            // Validate input
            if (request.getName() == null || request.getName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Name is required"));
            }
            if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
            }
            if (request.getPassword() == null || request.getPassword().length() < 8) {
                return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 8 characters"));
            }

            // Check for duplicate email
            if (userRepository.findByEmail(request.getEmail().trim().toLowerCase()).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "An account with this email already exists"));
            }

            // Create user
            User user = new User();
            user.setName(request.getName().trim());
            user.setEmail(request.getEmail().trim().toLowerCase());
            user.setUsername(request.getEmail().trim().toLowerCase()); // Use email as username
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
            user.setAuthProvider("LOCAL");
            user.setEmailVerified(false);
            user.setLastLoginAt(LocalDateTime.now());

            String sessionToken = java.util.UUID.randomUUID().toString();
            user.setSessionToken(sessionToken);

            userRepository.save(user);
            log.info("New user signed up with email: {}", user.getEmail());

            session.setAttribute("userId", user.getId());

            return ResponseEntity.ok(buildUserResponse(user));

        } catch (Exception e) {
            log.error("Sign up failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Sign up failed. Please try again."));
        }
    }

    /**
     * Login with email and password.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpSession session) {
        try {
            if (request.getEmail() == null || request.getPassword() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email and password are required"));
            }

            var userOpt = userRepository.findByEmail(request.getEmail().trim().toLowerCase());
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid email or password"));
            }

            User user = userOpt.get();

            // Check if this user was created via OAuth (no password set)
            if (user.getPasswordHash() == null) {
                String provider = user.getAuthProvider();
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error",
                                "This account uses " + provider + " sign-in. Please use the " + provider + " button to log in."));
            }

            if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid email or password"));
            }

            // Update session
            String sessionToken = java.util.UUID.randomUUID().toString();
            user.setSessionToken(sessionToken);
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);

            session.setAttribute("userId", user.getId());
            log.info("User {} logged in with email/password", user.getEmail());

            return ResponseEntity.ok(buildUserResponse(user));

        } catch (Exception e) {
            log.error("Login failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Login failed. Please try again."));
        }
    }

    // ==================== Google OAuth ====================

    /**
     * Login/signup with Google OAuth.
     * Frontend sends the Google ID token (credential) after Google Sign-In.
     */
    @PostMapping("/google")
    public ResponseEntity<?> googleAuth(@RequestBody GoogleTokenRequest request, HttpSession session) {
        try {
            if (request.getCredential() == null || request.getCredential().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Google credential is required"));
            }

            // Verify the Google ID token using Google's tokeninfo endpoint
            Map<String, Object> tokenInfo;
            try {
                tokenInfo = webClient.get()
                        .uri("https://oauth2.googleapis.com/tokeninfo?id_token=" + request.getCredential())
                        .retrieve()
                        .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                        .block();
            } catch (Exception e) {
                log.error("Failed to verify Google token", e);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid Google credential"));
            }

            if (tokenInfo == null || !googleClientId.equals(tokenInfo.get("aud"))) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid Google credential"));
            }

            String googleId = (String) tokenInfo.get("sub");
            String email = (String) tokenInfo.get("email");
            String name = (String) tokenInfo.get("name");
            String picture = (String) tokenInfo.get("picture");
            boolean emailVerified = "true".equals(String.valueOf(tokenInfo.get("email_verified")));

            // Upsert user: find by googleId first, then by email
            User user = userRepository.findByGoogleId(googleId)
                    .orElseGet(() -> userRepository.findByEmail(email).orElse(new User()));

            user.setGoogleId(googleId);
            user.setEmail(email);
            user.setName(name);
            user.setAvatarUrl(picture);
            user.setAuthProvider("GOOGLE");
            user.setEmailVerified(emailVerified);
            user.setLastLoginAt(LocalDateTime.now());

            if (user.getUsername() == null || user.getUsername().isEmpty()) {
                user.setUsername(email);
            }

            String sessionToken = java.util.UUID.randomUUID().toString();
            user.setSessionToken(sessionToken);

            userRepository.save(user);
            log.info("User {} authenticated with Google", email);

            session.setAttribute("userId", user.getId());

            return ResponseEntity.ok(buildUserResponse(user));

        } catch (Exception e) {
            log.error("Google auth failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Google authentication failed. Please try again."));
        }
    }

    // ==================== GitHub OAuth ====================

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
            user.setAuthProvider("GITHUB");
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

    // ==================== Common Endpoints ====================

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
                .map(user -> ResponseEntity.ok(buildUserResponse(user)))
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

    // ==================== Helpers ====================

    private Map<String, Object> buildUserResponse(User user) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", user.getId());
        response.put("githubId", user.getGithubId());
        response.put("username", user.getUsername());
        response.put("name", user.getName());
        response.put("email", user.getEmail());
        response.put("avatarUrl", user.getAvatarUrl());
        response.put("profileUrl", user.getProfileUrl());
        response.put("token", user.getSessionToken());
        response.put("authProvider", user.getAuthProvider());
        return response;
    }
}


package com.aicicd.platform.auth;

import com.aicicd.platform.config.AppProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * Authentication Controller handling:
 * 1. Local Email & Password Register & Login
 * 2. GitHub OAuth Flow
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppProperties props;
    private final UserRepository users;
    private final JwtService jwt;
    private final PasswordEncoder passwordEncoder;
    private final RestClient http = RestClient.create();

    public AuthController(AppProperties props, UserRepository users, JwtService jwt, PasswordEncoder passwordEncoder) {
        this.props = props;
        this.users = users;
        this.jwt = jwt;
        this.passwordEncoder = passwordEncoder;
    }

    public record RegisterRequest(String email, String password, String name) {}
    public record LoginRequest(String email, String password) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        if (req.email() == null || req.email().isBlank() || req.password() == null || req.password().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email and password are required."));
        }

        String email = req.email().trim().toLowerCase();
        if (users.findByEmail(email).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "An account with this email already exists."));
        }

        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setName(req.name() != null && !req.name().isBlank() ? req.name().trim() : email.split("@")[0]);
        user.setLogin(email.split("@")[0]);
        user.setPassword(passwordEncoder.encode(req.password()));
        users.save(user);

        String session = jwt.issue(user.getLogin());
        return ResponseEntity.ok(Map.of(
                "token", session,
                "email", user.getEmail(),
                "name", user.getName(),
                "login", user.getLogin()
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req.email() == null || req.email().isBlank() || req.password() == null || req.password().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email and password are required."));
        }

        String email = req.email().trim().toLowerCase();
        Optional<UserEntity> userOpt = users.findByEmail(email);

        if (userOpt.isEmpty() || userOpt.get().getPassword() == null ||
                !passwordEncoder.matches(req.password(), userOpt.get().getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid email or password."));
        }

        UserEntity user = userOpt.get();
        String session = jwt.issue(user.getLogin() != null ? user.getLogin() : user.getEmail());
        return ResponseEntity.ok(Map.of(
                "token", session,
                "email", user.getEmail(),
                "name", user.getName() != null ? user.getName() : "",
                "login", user.getLogin() != null ? user.getLogin() : ""
        ));
    }

    @GetMapping("/github")
    public Map<String, String> githubLoginUrl() {
        String url = "https://github.com/login/oauth/authorize"
                + "?client_id=" + props.github().clientId()
                + "&redirect_uri=" + props.github().oauthCallbackUrl()
                + "&scope=read:user,user:email";
        return Map.of("url", url);
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", props.github().clientId());
        form.add("client_secret", props.github().clientSecret());
        form.add("code", code);
        form.add("redirect_uri", props.github().oauthCallbackUrl());

        Map<?, ?> tokenResp = http.post()
                .uri("https://github.com/login/oauth/access_token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .body(form)
                .retrieve()
                .body(Map.class);

        String accessToken = tokenResp != null
                ? (String) tokenResp.get("access_token") : null;
        if (accessToken == null) {
            return ResponseEntity.status(302)
                    .location(URI.create(props.frontendUrl() + "/login?error=oauth"))
                    .build();
        }

        Map<?, ?> ghUser = http.get()
                .uri("https://api.github.com/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .body(Map.class);

        Long githubId = ((Number) ghUser.get("id")).longValue();
        String login = (String) ghUser.get("login");

        UserEntity user = users.findByGithubId(githubId).orElseGet(UserEntity::new);
        user.setGithubId(githubId);
        user.setLogin(login);
        user.setName((String) ghUser.get("name"));
        user.setAvatarUrl((String) ghUser.get("avatar_url"));
        user.setEmail((String) ghUser.get("email"));
        users.save(user);

        String session = jwt.issue(login);
        return ResponseEntity.status(302)
                .location(URI.create(props.frontendUrl() + "/login?token=" + session))
                .build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestAttribute(name = "githubLogin", required = false) String login) {
        if (login == null) return Map.of("authenticated", false);
        Optional<UserEntity> userOpt = users.findByLogin(login).or(() -> users.findByEmail(login));
        return userOpt
                .<Map<String, Object>>map(u -> Map.of(
                        "authenticated", true,
                        "login", u.getLogin() != null ? u.getLogin() : "",
                        "email", u.getEmail() != null ? u.getEmail() : "",
                        "name", u.getName() != null ? u.getName() : "",
                        "avatarUrl", u.getAvatarUrl() != null ? u.getAvatarUrl() : ""))
                .orElse(Map.of("authenticated", false));
    }
}

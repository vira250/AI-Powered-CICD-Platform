package com.aicicd.platform.webhook;

import com.aicicd.platform.config.AppProperties;
import com.aicicd.platform.github.GitHubApiClient;
import com.aicicd.platform.github.GitHubAppService;
import com.aicicd.platform.repo.ConnectedRepository;
import com.aicicd.platform.repo.ConnectedRepositoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

/**
 * GitHub App webhook receiver. Events handled:
 *  - installation / installation_repositories -> sync repos into repo_db
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final AppProperties props;
    private final GitHubAppService appService;
    private final GitHubApiClient github;
    private final ConnectedRepositoryRepository repos;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebhookController(AppProperties props, GitHubAppService appService,
                             GitHubApiClient github,
                             ConnectedRepositoryRepository repos) {
        this.props = props;
        this.appService = appService;
        this.github = github;
        this.repos = repos;
    }

    @PostMapping("/github")
    public ResponseEntity<?> receive(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader("X-GitHub-Event") String event,
            @RequestBody byte[] rawBody) throws Exception {

        if (!verifySignature(signature, rawBody)) {
            return ResponseEntity.status(401).body(Map.of("error", "bad signature"));
        }
        JsonNode payload = mapper.readTree(rawBody);
        log.info("GitHub webhook event: {}", event);

        switch (event) {
            case "installation", "installation_repositories" -> handleInstallation(payload);
            default -> log.debug("Ignored event {}", event);
        }
        return ResponseEntity.ok(Map.of("received", event));
    }

    // ---------------------------------------------------------------- util
    private boolean verifySignature(String signature, byte[] body) throws Exception {
        String secret = props.github().webhookSecret();
        if (secret == null || secret.isBlank()) return true; // dev mode
        if (signature == null || !signature.startsWith("sha256=")) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        return expected.equals(signature);
    }

    // ------------------------------------------------------- installation
    @Transactional("repoTransactionManager")
    void handleInstallation(JsonNode payload) {
        long installationId = payload.path("installation").path("id").asLong();
        JsonNode reposNode = payload.has("repositories")
                ? payload.get("repositories")
                : payload.get("repositories_added");
        if (reposNode == null || installationId == 0) return;
        for (JsonNode r : reposNode) {
            String fullName = r.path("full_name").asText();
            repos.findByFullName(fullName).orElseGet(() -> {
                ConnectedRepository cr = new ConnectedRepository();
                cr.setInstallationId(installationId);
                cr.setFullName(fullName);
                cr.setOwner(fullName.split("/")[0]);
                cr.setName(fullName.split("/")[1]);
                cr.setDefaultBranch(r.path("default_branch").asText("main"));
                return repos.save(cr);
            });
        }
    }
}

package com.aicicd.platform.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Handles root or post-installation redirects from GitHub App installer to the frontend.
 */
@RestController
public class RootRedirectController {

    private final AppProperties props;

    public RootRedirectController(AppProperties props) {
        this.props = props;
    }

    @GetMapping("/")
    public ResponseEntity<Void> rootRedirect(
            @RequestParam(name = "installation_id", required = false) String installationId,
            @RequestParam(name = "setup_action", required = false) String setupAction) {
        
        String targetUrl = props.frontendUrl();
        if (installationId != null && !installationId.isBlank()) {
            targetUrl += "/?installation_id=" + installationId;
        }
        return ResponseEntity.status(302)
                .location(URI.create(targetUrl))
                .build();
    }
}

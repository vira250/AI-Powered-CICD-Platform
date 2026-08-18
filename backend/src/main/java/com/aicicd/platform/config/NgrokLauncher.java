package com.aicicd.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Optionally starts an ngrok tunnel so GitHub webhooks can reach this
 * backend during local development (NGROK_ENABLED=true).
 */
@Component
public class NgrokLauncher implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(NgrokLauncher.class);

    private final AppProperties props;

    public NgrokLauncher(AppProperties props) {
        this.props = props;
    }

    @Override
    public void run(String... args) {
        if (!props.ngrok().enabled()) return;

        String port = System.getenv("PORT") != null ? System.getenv("PORT") : "8080";
        List<String> cmd = new ArrayList<>(List.of("ngrok", "http", port));
        if (!props.ngrok().authtoken().isBlank()) {
            cmd.add("--authtoken=" + props.ngrok().authtoken());
        }
        try {
            new ProcessBuilder(cmd).inheritIO().start();
            log.info("ngrok tunnel started — set the public URL + /api/webhooks/github "
                    + "as your GitHub App webhook URL");
        } catch (IOException e) {
            log.warn("NGROK_ENABLED=true but ngrok binary not found: {}", e.getMessage());
        }
    }
}

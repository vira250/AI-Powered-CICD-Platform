package com.aicicd.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the .env-driven application.yml properties. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String environment,
        String frontendUrl,
        String agentsUrl,
        String sessionSecret,
        Github github,
        Postgres postgres,
        Ngrok ngrok
) {
    public record Github(
            String appId,
            String appSlug,
            String clientId,
            String clientSecret,
            String webhookSecret,
            String privateKeyPath,
            String oauthCallbackUrl,
            String apiBase
    ) {}

    public record Postgres(
            String host,
            int port,
            String user,
            String password,
            String loginDb,
            String repoDb
    ) {
        public String jdbcUrl(String db) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + db;
        }
    }

    public record Ngrok(boolean enabled, String authtoken) {}
}

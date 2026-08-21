package com.aicicd.platform.orchestrator;

import com.aicicd.platform.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Bridge between the Spring Boot backend and the FastAPI AI Orchestrator.
 * The backend never talks to the LLM directly — it sends events here.
 */
@Component
public class OrchestratorClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorClient.class);
    private final String baseUrl;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OrchestratorClient(AppProperties props) {
        this.baseUrl = props.agentsUrl();
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Fires an event at the AI Orchestrator and returns its aggregated result. */
    public Map<String, Object> orchestrate(String event, Map<String, Object> payload) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "event", event,
                    "payload", payload != null ? payload : Map.of()
            );
            String json = mapper.writeValueAsString(requestBody);
            log.info("Sending request to AI Orchestrator: {} with body length {}", baseUrl + "/orchestrate", json.length());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/orchestrate"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                log.error("AI Orchestrator returned status {}: {}", resp.statusCode(), resp.body());
                throw new RuntimeException("AI Orchestrator returned " + resp.statusCode() + ": " + resp.body());
            }

            return mapper.readValue(resp.body(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Error communicating with AI Orchestrator: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to invoke AI Orchestrator for event " + event + ": " + e.getMessage(), e);
        }
    }

    public Map<String, Object> agentsHealth() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                return mapper.readValue(resp.body(), new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception ignored) {}
        return Map.of("status", "offline");
    }
}

package com.aicicd.platform.orchestrator;

import com.aicicd.platform.config.AppProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Bridge between the Spring Boot backend and the FastAPI AI Orchestrator.
 * The backend never talks to the LLM directly — it sends events here.
 */
@Component
public class OrchestratorClient {

    private final RestClient http;

    public OrchestratorClient(AppProperties props) {
        this.http = RestClient.builder().baseUrl(props.agentsUrl()).build();
    }

    /** Fires an event at the AI Orchestrator and returns its aggregated result. */
    public Map<String, Object> orchestrate(String event, Map<String, Object> payload) {
        return http.post()
                .uri("/orchestrate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("event", event, "payload", payload))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> agentsHealth() {
        return http.get().uri("/health").retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }
}

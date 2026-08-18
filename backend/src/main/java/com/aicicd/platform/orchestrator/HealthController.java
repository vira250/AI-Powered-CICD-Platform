package com.aicicd.platform.orchestrator;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Backend health + agents-service health passthrough for the dashboard. */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final OrchestratorClient orchestrator;

    public HealthController(OrchestratorClient orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> agents;
        try {
            agents = orchestrator.agentsHealth();
        } catch (Exception e) {
            agents = Map.of("status", "unreachable", "error", e.getMessage());
        }
        return Map.of("backend", "ok", "agents", agents);
    }
}

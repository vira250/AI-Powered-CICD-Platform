package com.aicicd.platform.deployment;

import com.aicicd.platform.orchestrator.OrchestratorClient;
import com.aicicd.platform.repo.ConnectedRepository;
import com.aicicd.platform.repo.ConnectedRepositoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Deployment & rollback endpoints. All heavy lifting (docker build/push,
 * health checks, auto-rollback) happens in the Deployment Agent inside the
 * agents service; this controller records version history in repo_db.
 */
@RestController
@RequestMapping("/api")
public class DeploymentController {

    private final DeploymentRecordRepository deployments;
    private final ConnectedRepositoryRepository repos;
    private final OrchestratorClient orchestrator;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeploymentController(DeploymentRecordRepository deployments,
                                ConnectedRepositoryRepository repos,
                                OrchestratorClient orchestrator) {
        this.deployments = deployments;
        this.repos = repos;
        this.orchestrator = orchestrator;
    }

    @GetMapping("/repos/{repoId}/deployments")
    public List<DeploymentRecord> history(@PathVariable Long repoId) {
        return deployments.findByRepositoryIdOrderByDeployedAtDesc(repoId);
    }

    @PostMapping("/repos/{repoId}/deploy")
    @Transactional("repoTransactionManager")
    public ResponseEntity<?> deploy(@PathVariable Long repoId,
                                    @RequestBody Map<String, Object> body) throws Exception {
        ConnectedRepository repo = repos.findById(repoId)
                .orElseThrow(() -> new IllegalArgumentException("repo not connected"));

        // current version becomes the rollback target
        String previousImage = deployments.findByRepositoryIdAndCurrentTrue(repoId)
                .map(DeploymentRecord::getImage).orElse("");

        Map<String, Object> result = orchestrator.orchestrate("deploy", Map.of(
                "repo", repo.getFullName(),
                "version", body.getOrDefault("version", ""),
                "workdir", body.getOrDefault("workdir", "."),
                "health_url", body.getOrDefault("healthUrl", ""),
                "previous_image", previousImage));

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        if (output == null) return ResponseEntity.badRequest().body(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> steps = (Map<String, Object>) output.get("steps");
        String status = (String) output.get("status");

        DeploymentRecord record = new DeploymentRecord();
        record.setRepository(repo);
        record.setVersion(steps != null ? String.valueOf(steps.get("version")) : "unknown");
        record.setImage(steps != null ? String.valueOf(steps.get("image")) : "");
        record.setStatus(switch (status) {
            case "deployed" -> DeploymentRecord.Status.DEPLOYED;
            case "rolled_back" -> DeploymentRecord.Status.ROLLED_BACK;
            case "unhealthy" -> DeploymentRecord.Status.UNHEALTHY;
            default -> DeploymentRecord.Status.FAILED;
        });
        record.setDetailJson(mapper.writeValueAsString(output));

        if (record.getStatus() == DeploymentRecord.Status.DEPLOYED) {
            // mark new version as current, demote the previous one
            deployments.findByRepositoryIdAndCurrentTrue(repoId)
                    .ifPresent(old -> { old.setCurrent(false); deployments.save(old); });
            record.setCurrent(true);
        }
        deployments.save(record);
        return ResponseEntity.ok(Map.of("deploymentId", record.getId(),
                "status", record.getStatus().name(), "detail", output));
    }

    /** Manual rollback — Rollback Manager restores the previous version. */
    @PostMapping("/repos/{repoId}/rollback")
    @Transactional("repoTransactionManager")
    public ResponseEntity<?> rollback(@PathVariable Long repoId) {
        ConnectedRepository repo = repos.findById(repoId)
                .orElseThrow(() -> new IllegalArgumentException("repo not connected"));

        List<DeploymentRecord> history =
                deployments.findByRepositoryIdOrderByDeployedAtDesc(repoId);
        DeploymentRecord current = history.stream().filter(DeploymentRecord::isCurrent)
                .findFirst().orElse(null);
        DeploymentRecord previous = history.stream()
                .filter(d -> !d.isCurrent()
                        && d.getStatus() == DeploymentRecord.Status.DEPLOYED)
                .findFirst().orElse(null);
        if (previous == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "no previous deployed version to roll back to"));
        }

        Map<String, Object> result = orchestrator.orchestrate("rollback", Map.of(
                "repo", repo.getFullName(),
                "previous_image", previous.getImage()));

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        boolean ok = output != null && "rolled_back".equals(output.get("status"));

        if (ok) {
            if (current != null) { current.setCurrent(false); deployments.save(current); }
            previous.setCurrent(true);
            deployments.save(previous);

            DeploymentRecord audit = new DeploymentRecord();
            audit.setRepository(repo);
            audit.setVersion(previous.getVersion());
            audit.setImage(previous.getImage());
            audit.setStatus(DeploymentRecord.Status.ROLLED_BACK);
            audit.setCurrent(false);
            deployments.save(audit);
        }
        return ResponseEntity.ok(Map.of("rolledBack", ok,
                "restoredVersion", previous.getVersion(), "detail",
                output != null ? output : result));
    }
}

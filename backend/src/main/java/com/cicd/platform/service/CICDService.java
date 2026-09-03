package com.cicd.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class CICDService {

    private static final Logger log = LoggerFactory.getLogger(CICDService.class);
    
    private final GitHubAppService gitHubAppService;
    private final WebClient webClient;

    public CICDService(GitHubAppService gitHubAppService) {
        this.gitHubAppService = gitHubAppService;
        this.webClient = WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * Trigger a mock CI/CD pipeline for a commit.
     */
    public void triggerPipeline(Long installationId, String owner, String repo, String headSha) {
        log.info("Triggering pipeline for {}/{} commit {}", owner, repo, headSha);
        
        CompletableFuture.runAsync(() -> {
            try {
                // 1. Get Installation Token
                String token = gitHubAppService.getInstallationToken(installationId);
                
                // 2. Create Check Run (In Progress)
                Map<String, Object> createBody = Map.of(
                        "name", "DeployHub CI/CD",
                        "head_sha", headSha,
                        "status", "in_progress",
                        "started_at", java.time.Instant.now().toString(),
                        "output", Map.of(
                                "title", "Pipeline started",
                                "summary", "The pipeline is currently running..."
                        )
                );
                
                Map checkRun = webClient.post()
                        .uri("/repos/{owner}/{repo}/check-runs", owner, repo)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(createBody)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();
                
                if (checkRun == null || !checkRun.containsKey("id")) {
                    log.error("Failed to create check run");
                    return;
                }
                
                Long checkRunId = ((Number) checkRun.get("id")).longValue();
                
                // 3. Simulate build time (e.g., 5 seconds)
                TimeUnit.SECONDS.sleep(5);
                
                // 4. Update Check Run (Completed)
                Map<String, Object> updateBody = Map.of(
                        "name", "DeployHub CI/CD",
                        "status", "completed",
                        "conclusion", "success",
                        "completed_at", java.time.Instant.now().toString(),
                        "output", Map.of(
                                "title", "Pipeline succeeded",
                                "summary", "All checks passed and the deployment was successful."
                        )
                );
                
                webClient.patch()
                        .uri("/repos/{owner}/{repo}/check-runs/{check_run_id}", owner, repo, checkRunId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(updateBody)
                        .retrieve()
                        .bodyToMono(Void.class)
                        .block();
                        
                log.info("Pipeline completed successfully for {}", headSha);
                
            } catch (Exception e) {
                log.error("Pipeline execution failed", e);
            }
        });
    }
}

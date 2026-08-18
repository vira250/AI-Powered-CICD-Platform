package com.aicicd.platform.github;

import com.aicicd.platform.config.AppProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Thin REST wrapper over the GitHub API, authenticated with installation tokens. */
@Component
public class GitHubApiClient {

    private static final String AUTH = HttpHeaders.AUTHORIZATION;

    private final String apiBase;
    private final RestClient http;

    public GitHubApiClient(AppProperties props) {
        this.apiBase = props.github().apiBase();
        this.http = RestClient.builder()
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** Repositories the GitHub App installation can access. */
    public List<Map<String, Object>> listInstallationRepos(String installationToken) {
        Map<?, ?> resp = http.get().uri(apiBase + "/installation/repositories")
                .header(AUTH, "Bearer " + installationToken)
                .retrieve().body(Map.class);
        List<Map<String, Object>> repos = new ArrayList<>();
        if (resp != null && resp.get("repositories") instanceof List<?> list) {
            for (Object o : list) repos.add(castRepo(o));
        }
        return repos;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castRepo(Object o) {
        return (Map<String, Object>) o;
    }

    /** Every file path in the repo (recursive git tree of the default branch). */
    public List<String> listFiles(String token, String fullName) {
        Map<?, ?> resp = http.get()
                .uri(apiBase + "/repos/{full}/git/trees/HEAD?recursive=1", fullName)
                .header(AUTH, "Bearer " + token)
                .retrieve().body(Map.class);
        List<String> files = new ArrayList<>();
        if (resp != null && resp.get("tree") instanceof List<?> tree) {
            for (Object o : tree) {
                Map<?, ?> node = (Map<?, ?>) o;
                if ("blob".equals(node.get("type"))) files.add((String) node.get("path"));
            }
        }
        return files;
    }

    /** Decoded content of a single file. */
    public String getFileContent(String token, String fullName, String path) {
        Map<?, ?> resp = http.get()
                .uri(apiBase + "/repos/{full}/contents/{path}", fullName, path)
                .header(AUTH, "Bearer " + token)
                .retrieve().body(Map.class);
        if (resp == null || resp.get("content") == null) return "";
        String encoded = resp.get("content").toString().replaceAll("\\s", "");
        return new String(Base64.getDecoder().decode(encoded));
    }

    /** Creates/updates a file (used to push .github/workflows/ai-ci-cd.yml). */
    public void commitFile(String token, String fullName, String path, String content,
                           String message, String branch) {
        // fetch existing sha if the file already exists (update instead of create)
        String sha = null;
        try {
            Map<?, ?> existing = http.get()
                    .uri(apiBase + "/repos/{full}/contents/{path}?ref={branch}",
                            fullName, path, branch)
                    .header(AUTH, "Bearer " + token)
                    .retrieve().body(Map.class);
            if (existing != null) sha = (String) existing.get("sha");
        } catch (Exception ignored) { /* new file */ }

        var body = new java.util.HashMap<String, Object>();
        body.put("message", message);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes()));
        body.put("branch", branch);
        if (sha != null) body.put("sha", sha);

        http.put().uri(apiBase + "/repos/{full}/contents/{path}", fullName, path)
                .header(AUTH, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity();
    }

    /** Recent workflow runs (GitHub Actions). */
    public List<Map<String, Object>> listWorkflowRuns(String token, String fullName) {
        Map<?, ?> resp = http.get()
                .uri(apiBase + "/repos/{full}/actions/runs?per_page=20", fullName)
                .header(AUTH, "Bearer " + token)
                .retrieve().body(Map.class);
        List<Map<String, Object>> runs = new ArrayList<>();
        if (resp != null && resp.get("workflow_runs") instanceof List<?> list) {
            for (Object o : list) runs.add(castRepo(o));
        }
        return runs;
    }

    public List<Map<String, Object>> listRunJobs(String token, String fullName, long runId) {
        Map<?, ?> resp = http.get()
                .uri(apiBase + "/repos/{full}/actions/runs/{id}/jobs", fullName, runId)
                .header(AUTH, "Bearer " + token)
                .retrieve().body(Map.class);
        List<Map<String, Object>> jobs = new ArrayList<>();
        if (resp != null && resp.get("jobs") instanceof List<?> list) {
            for (Object o : list) jobs.add(castRepo(o));
        }
        return jobs;
    }

    /** Downloads plain-text logs for a job (follows the redirect manually). */
    public String downloadJobLogs(String token, String fullName, long jobId) {
        ResponseEntity<String> first = http.get()
                .uri(apiBase + "/repos/{full}/actions/jobs/{id}/logs", fullName, jobId)
                .header(AUTH, "Bearer " + token)
                .exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                        .headers(res.getHeaders()).body(res.bodyTo(String.class)));
        if (first.getStatusCode().is3xxRedirection()) {
            URI location = first.getHeaders().getLocation();
            if (location != null) {
                return http.get().uri(location).retrieve().body(String.class);
            }
        }
        return first.getBody() != null ? first.getBody() : "";
    }

    /** Unified diff of a pull request. */
    public String getPullRequestDiff(String token, String fullName, int prNumber) {
        return http.get()
                .uri(apiBase + "/repos/{full}/pulls/{n}", fullName, prNumber)
                .header(AUTH, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
                .retrieve().body(String.class);
    }

    /** Posts a comment on a pull request (issues API). */
    public void postPrComment(String token, String fullName, int prNumber, String body) {
        http.post()
                .uri(apiBase + "/repos/{full}/issues/{n}/comments", fullName, prNumber)
                .header(AUTH, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", body))
                .retrieve().toBodilessEntity();
    }

    public Map<String, Object> getRun(String token, String fullName, long runId) {
        return http.get()
                .uri(apiBase + "/repos/{full}/actions/runs/{id}", fullName, runId)
                .header(AUTH, "Bearer " + token)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }
}

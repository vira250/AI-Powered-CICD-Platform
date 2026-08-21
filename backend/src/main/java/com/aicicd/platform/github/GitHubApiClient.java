package com.aicicd.platform.github;

import com.aicicd.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Thin REST wrapper over the GitHub API, authenticated with installation tokens. */
@Component
public class GitHubApiClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubApiClient.class);
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

    /** Repositories the GitHub App installation can access (fetches all pages). */
    public List<Map<String, Object>> listInstallationRepos(String installationToken) {
        List<Map<String, Object>> repos = new ArrayList<>();
        int page = 1;
        int perPage = 100;
        while (true) {
            Map<?, ?> resp = http.get()
                    .uri(URI.create(apiBase + "/installation/repositories?per_page=" + perPage + "&page=" + page))
                    .header(AUTH, "Bearer " + installationToken)
                    .retrieve().body(Map.class);
            if (resp != null && resp.get("repositories") instanceof List<?> list && !list.isEmpty()) {
                for (Object o : list) repos.add(castRepo(o));
                Number totalCount = (Number) resp.get("total_count");
                if (totalCount != null && repos.size() >= totalCount.intValue()) {
                    break;
                }
                if (list.size() < perPage) {
                    break;
                }
                page++;
            } else {
                break;
            }
        }
        return repos;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castRepo(Object o) {
        return (Map<String, Object>) o;
    }

    /** Every file path in the repo (recursive git tree of the default branch). */
    public List<String> listFiles(String token, String fullName) {
        return listFiles(token, fullName, "main");
    }

    public List<String> listFiles(String token, String fullName, String branch) {
        String targetBranch = (branch != null && !branch.isBlank()) ? branch : "main";

        // 1. Try branch git tree (e.g. main)
        try {
            URI uri = URI.create(apiBase + "/repos/" + fullName + "/git/trees/" + targetBranch + "?recursive=1");
            Map<?, ?> resp = http.get()
                    .uri(uri)
                    .header(AUTH, "Bearer " + token)
                    .retrieve().body(Map.class);
            List<String> files = extractTreeFiles(resp);
            if (!files.isEmpty()) return files;
        } catch (Exception e) {
            log.info("Git tree for {} on branch '{}' failed: {}. Trying fallback branches...", fullName, targetBranch, e.getMessage());
        }

        // 2. Try 'master' fallback if target wasn't master
        if (!"master".equalsIgnoreCase(targetBranch)) {
            try {
                URI uri = URI.create(apiBase + "/repos/" + fullName + "/git/trees/master?recursive=1");
                Map<?, ?> resp = http.get()
                        .uri(uri)
                        .header(AUTH, "Bearer " + token)
                        .retrieve().body(Map.class);
                List<String> files = extractTreeFiles(resp);
                if (!files.isEmpty()) return files;
            } catch (Exception ignored) {}
        }

        // 3. Query repository metadata for exact default_branch
        try {
            URI repoUri = URI.create(apiBase + "/repos/" + fullName);
            Map<?, ?> repoInfo = http.get()
                    .uri(repoUri)
                    .header(AUTH, "Bearer " + token)
                    .retrieve().body(Map.class);
            if (repoInfo != null && repoInfo.get("default_branch") != null) {
                String actualDefault = (String) repoInfo.get("default_branch");
                URI treeUri = URI.create(apiBase + "/repos/" + fullName + "/git/trees/" + actualDefault + "?recursive=1");
                Map<?, ?> resp = http.get()
                        .uri(treeUri)
                        .header(AUTH, "Bearer " + token)
                        .retrieve().body(Map.class);
                List<String> files = extractTreeFiles(resp);
                if (!files.isEmpty()) return files;
            }
        } catch (Exception ignored) {}

        // 4. Fallback to /contents API (root files)
        try {
            URI contentsUri = URI.create(apiBase + "/repos/" + fullName + "/contents");
            Object raw = http.get()
                    .uri(contentsUri)
                    .header(AUTH, "Bearer " + token)
                    .retrieve().body(Object.class);
            List<String> files = new ArrayList<>();
            if (raw instanceof List<?> contents) {
                for (Object o : contents) {
                    if (o instanceof Map<?, ?> item) {
                        files.add(String.valueOf(item.get("path")));
                    }
                }
            }
            return files;
        } catch (Exception ex) {
            log.warn("All file listing strategies for {} failed: {}", fullName, ex.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<String> extractTreeFiles(Map<?, ?> resp) {
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
        try {
            URI uri = URI.create(apiBase + "/repos/" + fullName + "/contents/" + path);
            Map<?, ?> resp = http.get()
                    .uri(uri)
                    .header(AUTH, "Bearer " + token)
                    .retrieve().body(Map.class);
            if (resp == null || resp.get("content") == null) return "";
            String encoded = resp.get("content").toString().replaceAll("\\s", "");
            return new String(Base64.getDecoder().decode(encoded));
        } catch (Exception e) {
            log.warn("Could not read file {} from {}: {}", path, fullName, e.getMessage());
            return "";
        }
    }

    /** Creates/updates a file (used to push .github/workflows/ai-ci-cd.yml). */
    public void commitFile(String token, String fullName, String path, String content,
                           String message, String branch) {
        // fetch existing sha if the file already exists (update instead of create)
        String sha = null;
        try {
            String url = apiBase + "/repos/" + fullName + "/contents/" + path + (branch != null ? "?ref=" + branch : "");
            Map<?, ?> existing = http.get()
                    .uri(URI.create(url))
                    .header(AUTH, "Bearer " + token)
                    .retrieve().body(Map.class);
            if (existing != null) sha = (String) existing.get("sha");
        } catch (Exception ignored) { /* new file */ }

        var body = new java.util.HashMap<String, Object>();
        body.put("message", message);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes()));
        if (branch != null && !branch.isBlank()) {
            body.put("branch", branch);
        }
        if (sha != null) body.put("sha", sha);

        URI putUri = URI.create(apiBase + "/repos/" + fullName + "/contents/" + path);
        try {
            http.put().uri(putUri)
                    .header(AUTH, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            // If committing with explicit branch fails on empty repository, retry without branch parameter
            if (body.containsKey("branch")) {
                body.remove("branch");
                http.put().uri(putUri)
                        .header(AUTH, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).body(body)
                        .retrieve().toBodilessEntity();
            } else {
                throw e;
            }
        }
    }

    /** Recent workflow runs (GitHub Actions). */
    public List<Map<String, Object>> listWorkflowRuns(String token, String fullName) {
        try {
            URI uri = URI.create(apiBase + "/repos/" + fullName + "/actions/runs?per_page=20");
            Map<?, ?> resp = http.get()
                    .uri(uri)
                    .header(AUTH, "Bearer " + token)
                    .retrieve().body(Map.class);
            List<Map<String, Object>> runs = new ArrayList<>();
            if (resp != null && resp.get("workflow_runs") instanceof List<?> list) {
                for (Object o : list) runs.add(castRepo(o));
            }
            return runs;
        } catch (Exception e) {
            log.warn("Could not list workflow runs for {}: {}", fullName, e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> listRunJobs(String token, String fullName, long runId) {
        try {
            URI uri = URI.create(apiBase + "/repos/" + fullName + "/actions/runs/" + runId + "/jobs");
            Map<?, ?> resp = http.get()
                    .uri(uri)
                    .header(AUTH, "Bearer " + token)
                    .retrieve().body(Map.class);
            List<Map<String, Object>> jobs = new ArrayList<>();
            if (resp != null && resp.get("jobs") instanceof List<?> list) {
                for (Object o : list) jobs.add(castRepo(o));
            }
            return jobs;
        } catch (Exception e) {
            log.warn("Could not list run jobs for run {} on {}: {}", runId, fullName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Downloads plain-text logs for a job (follows the redirect manually). */
    public String downloadJobLogs(String token, String fullName, long jobId) {
        URI uri = URI.create(apiBase + "/repos/" + fullName + "/actions/jobs/" + jobId + "/logs");
        ResponseEntity<String> first = http.get()
                .uri(uri)
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
        URI uri = URI.create(apiBase + "/repos/" + fullName + "/pulls/" + prNumber);
        return http.get()
                .uri(uri)
                .header(AUTH, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
                .retrieve().body(String.class);
    }

    /** Posts a comment on a pull request (issues API). */
    public void postPrComment(String token, String fullName, int prNumber, String body) {
        URI uri = URI.create(apiBase + "/repos/" + fullName + "/issues/" + prNumber + "/comments");
        http.post()
                .uri(uri)
                .header(AUTH, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", body))
                .retrieve().toBodilessEntity();
    }

    public Map<String, Object> getRun(String token, String fullName, long runId) {
        URI uri = URI.create(apiBase + "/repos/" + fullName + "/actions/runs/" + runId);
        return http.get()
                .uri(uri)
                .header(AUTH, "Bearer " + token)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }
}

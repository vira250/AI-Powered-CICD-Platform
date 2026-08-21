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

    /**
     * Constructs a comprehensive RepositoryContext containing directory structure,
     * file metadata, secret masking, and key configuration manifest contents.
     */
    public Map<String, Object> getRepositoryContext(String token, String fullName, String branch) {
        String targetBranch = (branch != null && !branch.isBlank()) ? branch : "main";
        Map<String, Object> context = new java.util.HashMap<>();
        List<Map<String, Object>> structure = new ArrayList<>();
        List<Map<String, Object>> files = new ArrayList<>();
        long totalBytes = 0;
        String commitSha = null;

        // 1. Fetch recursive tree
        Map<?, ?> treeResp = null;
        try {
            URI uri = URI.create(apiBase + "/repos/" + fullName + "/git/trees/" + targetBranch + "?recursive=1");
            treeResp = http.get().uri(uri).header(AUTH, "Bearer " + token).retrieve().body(Map.class);
        } catch (Exception ignored) {}

        if (treeResp == null || !(treeResp.get("tree") instanceof List<?>)) {
            try {
                URI repoUri = URI.create(apiBase + "/repos/" + fullName);
                Map<?, ?> repoInfo = http.get().uri(repoUri).header(AUTH, "Bearer " + token).retrieve().body(Map.class);
                if (repoInfo != null && repoInfo.get("default_branch") != null) {
                    targetBranch = (String) repoInfo.get("default_branch");
                    URI uri = URI.create(apiBase + "/repos/" + fullName + "/git/trees/" + targetBranch + "?recursive=1");
                    treeResp = http.get().uri(uri).header(AUTH, "Bearer " + token).retrieve().body(Map.class);
                }
            } catch (Exception ignored) {}
        }

        if (treeResp != null) {
            commitSha = (String) treeResp.get("sha");
            if (treeResp.get("tree") instanceof List<?> treeList) {
                for (Object o : treeList) {
                    if (o instanceof Map<?, ?> node) {
                        String path = (String) node.get("path");
                        String type = (String) node.get("type");
                        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                        String sha = (String) node.get("sha");
                        long size = node.get("size") instanceof Number n ? n.longValue() : 0L;

                        if ("tree".equals(type)) {
                            structure.add(Map.of("path", path, "name", name, "type", "directory"));
                        } else if ("blob".equals(type)) {
                            boolean isBinary = isBinaryFile(path);
                            boolean isSecret = isSecretFile(path);
                            String fileType = isBinary ? "binary" : "text";

                            structure.add(Map.of("path", path, "name", name, "type", fileType));

                            Map<String, Object> fileObj = new java.util.HashMap<>();
                            fileObj.put("path", path);
                            fileObj.put("name", name);
                            fileObj.put("type", fileType);
                            fileObj.put("size", size);
                            fileObj.put("sha", sha);
                            fileObj.put("secret", isSecret);

                            if (isSecret) {
                                fileObj.put("content", "[REDACTED: Sensitive file contents masked for security]");
                            } else if (isBinary) {
                                fileObj.put("content", null);
                            } else if (isKeyManifestOrConfigFile(path) && size <= 50_000) {
                                String content = getFileContent(token, fullName, path);
                                fileObj.put("content", content);
                                totalBytes += (content != null ? content.length() : 0);
                            } else {
                                fileObj.put("content", null);
                            }
                            files.add(fileObj);
                        }
                    }
                }
            }
        }

        String[] parts = fullName.split("/");
        String owner = parts.length > 0 ? parts[0] : "";
        String repoName = parts.length > 1 ? parts[1] : fullName;

        Map<String, Object> repoMetadata = new java.util.HashMap<>();
        repoMetadata.put("githubRepositoryId", 0L);
        repoMetadata.put("owner", owner);
        repoMetadata.put("repositoryName", repoName);
        repoMetadata.put("branch", targetBranch);
        repoMetadata.put("commitSha", commitSha != null ? commitSha : "");

        context.put("repository", repoMetadata);
        context.put("structure", structure);
        context.put("files", files);
        context.put("totalBytes", totalBytes);
        context.put("truncated", false);
        context.put("message", "Complete repository context successfully constructed from GitHub.");
        return context;
    }

    private static boolean isBinaryFile(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".ico") || lower.endsWith(".pdf")
                || lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".gz")
                || lower.endsWith(".jar") || lower.endsWith(".war") || lower.endsWith(".exe")
                || lower.endsWith(".class") || lower.endsWith(".bin") || lower.endsWith(".woff")
                || lower.endsWith(".woff2") || lower.endsWith(".ttf") || lower.endsWith(".eot");
    }

    private static boolean isSecretFile(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".env") || lower.contains(".env.") || lower.endsWith(".pem")
                || lower.endsWith(".key") || lower.endsWith(".pkcs12") || lower.endsWith(".pfx")
                || lower.contains("id_rsa") || lower.contains("credentials") || lower.endsWith(".keystore");
    }

    private static boolean isKeyManifestOrConfigFile(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith("pom.xml") || lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts")
                || lower.endsWith("settings.gradle") || lower.endsWith("package.json") || lower.endsWith("requirements.txt")
                || lower.endsWith("pyproject.toml") || lower.endsWith("pipfile") || lower.endsWith("setup.py")
                || lower.endsWith("go.mod") || lower.endsWith("go.sum") || lower.endsWith("cargo.toml")
                || lower.endsWith(".csproj") || lower.endsWith(".sln") || lower.endsWith("composer.json")
                || lower.endsWith("gemfile") || lower.endsWith("dockerfile") || lower.endsWith("docker-compose.yml")
                || lower.endsWith("application.properties") || lower.endsWith("application.yml")
                || lower.endsWith("app.py") || lower.endsWith("main.py") || lower.endsWith("main.go")
                || lower.endsWith("application.java");
    }

    /** Downloads plain-text logs for a job (follows the redirect manually or via HttpClient). */
    public String downloadJobLogs(String token, String fullName, long jobId) {
        try {
            java.net.http.HttpClient logClient = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build();

            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/repos/" + fullName + "/actions/jobs/" + jobId + "/logs"))
                    .header(AUTH, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> resp = logClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return resp.body();
            } else {
                log.warn("Failed to download job logs for job {} (status {}): {}", jobId, resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("Exception downloading job logs for job {}: {}", jobId, e.getMessage());
        }
        return "";
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

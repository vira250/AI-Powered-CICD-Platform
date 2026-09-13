package com.cicd.platform.service;

import com.cicd.platform.dto.GithubRepositoryDTO;
import com.cicd.platform.dto.GithubRepositoryOwnerDTO;
import com.cicd.platform.exception.GithubApiException;
import com.cicd.platform.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);
    private static final int MAX_PER_PAGE = 100;

    private final WebClient githubApiClient;
    private final WebClient githubAuthClient;
    private final ObjectMapper objectMapper;
    private final GitHubAppService gitHubAppService;

    @Value("${github.client.id}")
    private String clientId;

    @Value("${github.client.secret}")
    private String clientSecret;

    public GitHubService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper, @Value("${github.api.base-url}") String githubApiBaseUrl, GitHubAppService gitHubAppService) {
        this.githubApiClient = webClientBuilder
                .baseUrl(githubApiBaseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.githubAuthClient = webClientBuilder
                .baseUrl("https://github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
        this.objectMapper = objectMapper;
        this.gitHubAppService = gitHubAppService;
    }

    public String exchangeCodeForToken(String code) {
        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", clientId);
            body.add("client_secret", clientSecret);
            body.add("code", code);

            JsonNode response = githubAuthClient.post()
                    .uri("/login/oauth/access_token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null && response.has("access_token")) {
                return response.get("access_token").asText();
            }

            throw new GithubApiException(HttpStatus.BAD_GATEWAY, "GitHub did not return an access token");
        } catch (WebClientResponseException exception) {
            throw mapGithubException(exception, "Unable to exchange OAuth code for access token");
        } catch (GithubApiException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("OAuth code exchange failed", exception);
            throw new GithubApiException(HttpStatus.BAD_GATEWAY, "GitHub OAuth token exchange failed");
        }
    }

    public Map<String, Object> getUserProfile(String accessToken) {
        JsonNode response = fetchUserNode(accessToken);

        Map<String, Object> profile = new HashMap<>();
        if (response != null) {
            profile.put("id", response.path("id").asLong());
            profile.put("login", response.path("login").asText(""));
            profile.put("name", response.path("name").isNull() || response.path("name").asText("").isBlank()
                    ? response.path("login").asText("")
                    : response.path("name").asText(""));
            profile.put("email", response.path("email").isNull() ? "" : response.path("email").asText(""));
            profile.put("avatar_url", response.path("avatar_url").asText(""));
            profile.put("html_url", response.path("html_url").asText(""));
        }
        return profile;
    }

    public List<GithubRepositoryDTO> listRepositories(User user, Integer page, Integer perPage) {
        boolean isInstallation = user.getInstallationId() != null;
        String accessToken = isInstallation ? gitHubAppService.getInstallationToken(user.getInstallationId()) : requireAccessToken(user);
        int pageSize = normalizePerPage(perPage);

        if (page == null) {
            return fetchAllRepositories(accessToken, pageSize, isInstallation);
        }

        return fetchRepositoriesPage(accessToken, normalizePage(page), pageSize, isInstallation);
    }

    public List<Map<String, Object>> getUserRepos(User user, int page, int perPage, String sort) {
        boolean isInstallation = user.getInstallationId() != null;
        String accessToken = isInstallation ? gitHubAppService.getInstallationToken(user.getInstallationId()) : requireAccessToken(user);
        List<GithubRepositoryDTO> repositories = fetchRepositoriesPage(accessToken, normalizePage(page), normalizePerPage(perPage), isInstallation);
        List<Map<String, Object>> result = new ArrayList<>();
        for (GithubRepositoryDTO repository : repositories) {
            result.add(toLegacyRepoMap(repository));
        }
        return result;
    }

    public Map<String, Object> getRepoDetails(User user, String owner, String repoName) {
        boolean isInstallation = user.getInstallationId() != null;
        String accessToken = isInstallation ? gitHubAppService.getInstallationToken(user.getInstallationId()) : requireAccessToken(user);
        JsonNode response = fetchRepoNode(accessToken, owner, repoName);
        return mapRepoNode(response);
    }

    public Map<String, Object> getRepoStructure(User user, String owner, String repoName, String branch) {
        boolean isInstallation = user.getInstallationId() != null;
        String accessToken = isInstallation ? gitHubAppService.getInstallationToken(user.getInstallationId()) : requireAccessToken(user);
        String targetBranch = branch;
        if (targetBranch == null || targetBranch.isBlank()) {
            Map<String, Object> details = getRepoDetails(user, owner, repoName);
            targetBranch = (String) details.getOrDefault("default_branch", "main");
        }

        JsonNode branchResponse = fetchJsonNode(githubApiClient.get()
                .uri("/repos/{owner}/{repo}/branches/{branch}", owner, repoName, targetBranch)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));

        JsonNode treeShaNode = branchResponse.path("commit").path("commit").path("tree").path("sha");
        String treeSha = treeShaNode.isMissingNode() ? null : treeShaNode.asText(null);
        if (treeSha == null || treeSha.isBlank()) {
            throw new GithubApiException(HttpStatus.NOT_FOUND, "Tree not found for repository branch");
        }

        JsonNode treeResponse = fetchJsonNode(githubApiClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/git/trees/{treeSha}")
                        .queryParam("recursive", "1")
                        .build(owner, repoName, treeSha))
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", repoName);
        root.put("path", "");
        root.put("type", "dir");
        root.put("children", new ArrayList<Map<String, Object>>());

        int[] counts = new int[] {0, 0};
        if (treeResponse != null && treeResponse.has("tree") && treeResponse.get("tree").isArray()) {
            for (JsonNode entry : treeResponse.get("tree")) {
                String path = entry.path("path").asText("");
                String type = entry.path("type").asText("");
                if (path.isBlank()) {
                    continue;
                }

                if ("tree".equals(type)) {
                    counts[0]++;
                } else if ("blob".equals(type)) {
                    counts[1]++;
                }

                insertTreeEntry(root, path, type, entry);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", targetBranch);
        result.put("treeSha", treeSha);
        result.put("folderCount", counts[0]);
        result.put("fileCount", counts[1]);
        result.put("tree", root);
        return result;
    }

    /**
     * Fetches branch info JSON from GitHub API, including commit SHA and tree SHA.
     * Used by RepositoryContextBuilder.
     */
    public JsonNode getBranchInfo(User user, String owner, String repoName, String branch) {
        boolean isInstallation = user.getInstallationId() != null;
        String accessToken = isInstallation ? gitHubAppService.getInstallationToken(user.getInstallationId()) : requireAccessToken(user);
        return fetchJsonNode(githubApiClient.get()
                .uri("/repos/{owner}/{repo}/branches/{branch}", owner, repoName, branch)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
    }

    /**
     * Fetches the complete recursive git tree from GitHub API.
     * Used by RepositoryContextBuilder.
     */
    public JsonNode getRecursiveTree(User user, String owner, String repoName, String treeSha) {
        boolean isInstallation = user.getInstallationId() != null;
        String accessToken = isInstallation ? gitHubAppService.getInstallationToken(user.getInstallationId()) : requireAccessToken(user);
        return fetchJsonNode(githubApiClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/git/trees/{treeSha}")
                        .queryParam("recursive", "1")
                        .build(owner, repoName, treeSha))
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
    }

    public List<Map<String, Object>> listWorkflowRuns(User user, String owner, String repoName) {
        String accessToken = getActionsAccessToken(user);
        JsonNode response = fetchJsonNode(githubApiClient.get()
                .uri(uriBuilder -> uriBuilder.path("/repos/{owner}/{repo}/actions/runs")
                        .queryParam("per_page", MAX_PER_PAGE).build(owner, repoName))
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
        List<Map<String, Object>> runs = new ArrayList<>();
        if (response != null && response.path("workflow_runs").isArray()) {
            for (JsonNode run : response.path("workflow_runs")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", run.path("id").asLong());
                item.put("name", run.path("name").asText("Workflow"));
                item.put("status", run.path("status").asText("unknown"));
                item.put("conclusion", jsonText(run.path("conclusion")));
                item.put("head_branch", run.path("head_branch").asText(""));
                item.put("head_sha", run.path("head_sha").asText(""));
                // This identifies the workflow file that created the run, for example
                // .github/workflows/ci.yml@refs/heads/main.
                item.put("path", run.path("path").asText(""));
                item.put("created_at", run.path("created_at").asText(""));
                item.put("updated_at", run.path("updated_at").asText(""));
                runs.add(item);
            }
        }
        return runs;
    }

    public List<Map<String, Object>> listWorkflowJobs(User user, String owner, String repoName, long runId) {
        String accessToken = getActionsAccessToken(user);
        JsonNode response = fetchJsonNode(githubApiClient.get()
                .uri(uriBuilder -> uriBuilder.path("/repos/{owner}/{repo}/actions/runs/{runId}/jobs")
                        .queryParam("per_page", 100).build(owner, repoName, runId))
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
        List<Map<String, Object>> jobs = new ArrayList<>();
        if (response != null && response.path("jobs").isArray()) {
            for (JsonNode job : response.path("jobs")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", job.path("id").asLong());
                item.put("name", job.path("name").asText("Job"));
                item.put("status", job.path("status").asText("unknown"));
                item.put("conclusion", jsonText(job.path("conclusion")));
                item.put("started_at", job.path("started_at").asText(""));
                item.put("completed_at", job.path("completed_at").asText(""));
                item.put("steps", objectMapper.convertValue(job.path("steps"), List.class));
                jobs.add(item);
            }
        }
        return jobs;
    }

    public String getWorkflowJobLogs(User user, String owner, String repoName, long jobId) {
        String accessToken = getActionsAccessToken(user);
        byte[] archive = githubApiClient.get()
                .uri("/repos/{owner}/{repo}/actions/jobs/{jobId}/logs", owner, repoName, jobId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .exchangeToMono(response -> {
                    if (response.statusCode().is3xxRedirection()) {
                        URI location = response.headers().asHttpHeaders().getLocation();
                        if (location == null) {
                            return Mono.error(new GithubApiException(HttpStatus.BAD_GATEWAY,
                                    "GitHub returned a log redirect without a location"));
                        }
                        return WebClient.builder().build().get()
                                .uri(location)
                                .retrieve()
                                .bodyToMono(byte[].class);
                    }
                    return response.bodyToMono(byte[].class);
                })
                .block();
        if (archive == null || archive.length == 0) {
            throw new GithubApiException(HttpStatus.NOT_FOUND, "GitHub returned an empty job log archive");
        }
        return decodeJobLogs(archive);
    }

    private String getActionsAccessToken(User user) {
        boolean isInstallation = user.getInstallationId() != null;
        return isInstallation ? gitHubAppService.getInstallationToken(user.getInstallationId()) : requireAccessToken(user);
    }

    private String unzipLogs(byte[] archive) {
        StringBuilder logs = new StringBuilder();
        try (InputStream input = new ByteArrayInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    int read;
                    while ((read = zip.read(buffer)) != -1 && logs.length() < 120_000) {
                        logs.append(new String(buffer, 0, Math.min(read, 120_000 - logs.length()), StandardCharsets.UTF_8));
                    }
                    logs.append('\n');
                }
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw new GithubApiException(HttpStatus.BAD_GATEWAY, "Unable to read GitHub job log archive");
        }
        return logs.toString();
    }

    private String decodeJobLogs(byte[] archive) {
        // GitHub's job-log endpoint redirects to a plain-text file. Keep ZIP support
        // for compatibility with older responses or alternate GitHub hosts.
        if (archive.length < 4
                || archive[0] != 'P'
                || archive[1] != 'K'
                || archive[2] != 3
                || archive[3] != 4) {
            return new String(archive, StandardCharsets.UTF_8);
        }
        return unzipLogs(archive);
    }

    public Map<String, Object> pushFile(User user, String owner, String repo,
                                        String path, String content, String commitMessage,
                                        String branch) {
        List<String> tokensToTry = new ArrayList<>();
        if (user.getAccessToken() != null && !user.getAccessToken().isBlank()) {
            tokensToTry.add(user.getAccessToken());
        }
        if (user.getInstallationId() != null) {
            try {
                String instToken = gitHubAppService.getInstallationToken(user.getInstallationId());
                if (instToken != null && !tokensToTry.contains(instToken)) {
                    tokensToTry.add(instToken);
                }
            } catch (Exception e) {
                log.warn("Failed to get installation token for push: {}", e.getMessage());
            }
        }

        if (tokensToTry.isEmpty()) {
            throw new GithubApiException(HttpStatus.UNAUTHORIZED, "No valid access token available for pushing to GitHub");
        }

        GithubApiException lastError = null;
        for (String accessToken : tokensToTry) {
            try {
                return executePush(accessToken, owner, repo, path, content, commitMessage, branch);
            } catch (GithubApiException e) {
                log.warn("Push failed with token (status {}): {}. Trying fallback token...", e.getStatus(), e.getMessage());
                lastError = e;
            }
        }

        throw lastError != null ? lastError : new GithubApiException(HttpStatus.FORBIDDEN, "Push to GitHub failed with all available tokens");
    }

    private Map<String, Object> executePush(String accessToken, String owner, String repo,
                                            String path, String content, String commitMessage,
                                            String branch) {
        String sha = null;
        try {
            JsonNode existing = fetchJsonNode(githubApiClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/contents/{path}")
                            .queryParam("ref", branch != null ? branch : "main")
                            .build(owner, repo, path))
                    .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));

            if (existing != null && existing.has("sha")) {
                sha = existing.get("sha").asText();
            }
        } catch (GithubApiException exception) {
            if (exception.getStatus() != HttpStatus.NOT_FOUND) {
                throw exception;
            }
        }

        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> body = new HashMap<>();
        body.put("message", commitMessage != null && !commitMessage.isBlank() ? commitMessage : "Update " + path);
        body.put("content", encodedContent);
        if (branch != null && !branch.isBlank()) {
            body.put("branch", branch);
        }
        if (sha != null) {
            body.put("sha", sha);
        }

        try {
            JsonNode response = fetchJsonNode(githubApiClient.put()
                    .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                    .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body));

            Map<String, Object> result = new HashMap<>();
            if (response != null) {
                result.put("success", true);
                if (response.has("content")) {
                    result.put("path", response.get("content").path("path").asText(""));
                    result.put("sha", response.get("content").path("sha").asText(""));
                    result.put("html_url", response.get("content").path("html_url").asText(""));
                }
                if (response.has("commit")) {
                    result.put("commit_sha", response.get("commit").path("sha").asText(""));
                    result.put("commit_message", response.get("commit").path("message").asText(""));
                }
            }
            return result;
        } catch (GithubApiException e) {
            if (e.getStatus() == HttpStatus.FORBIDDEN) {
                throw new GithubApiException(HttpStatus.FORBIDDEN, 
                    "GitHub 403 Forbidden: Pushing workflow files (.github/workflows/*) requires: " +
                    "1) 'Contents: Read & write' and 'Workflows: Read & write' in GitHub App Permissions (Permissions -> Repository permissions). " +
                    "2) Approve updated permissions in your GitHub App installation, then log out and log back in to refresh your OAuth token with the 'workflow' scope.");
            }
            throw e;
        }
    }

    public Map<String, Object> getFileContent(User user, String owner, String repo, String path, String branch) {
        boolean isInstallation = user.getInstallationId() != null;
        String accessToken = isInstallation ? gitHubAppService.getInstallationToken(user.getInstallationId()) : requireAccessToken(user);

        String targetBranch = branch != null && !branch.isBlank() ? branch : "main";
        JsonNode response = fetchJsonNode(githubApiClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/contents/{path}")
                        .queryParam("ref", targetBranch)
                        .build(owner, repo, path))
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));

        Map<String, Object> result = new LinkedHashMap<>();
        if (response != null && response.has("content")) {
            String rawBase64 = response.get("content").asText().replaceAll("\\s+", "");
            byte[] decodedBytes = Base64.getDecoder().decode(rawBase64);
            String textContent = new String(decodedBytes, StandardCharsets.UTF_8);

            result.put("name", response.path("name").asText(""));
            result.put("path", response.path("path").asText(""));
            result.put("sha", response.path("sha").asText(""));
            result.put("size", response.path("size").asLong(0));
            result.put("html_url", response.path("html_url").asText(""));
            result.put("content", textContent);
            result.put("encoding", "utf-8");
            result.put("branch", targetBranch);
        }
        return result;
    }

    private List<GithubRepositoryDTO> fetchAllRepositories(String accessToken, int perPage, boolean isInstallationToken) {
        List<GithubRepositoryDTO> repositories = new ArrayList<>();
        int page = 1;

        while (true) {
            List<GithubRepositoryDTO> nextPage = fetchRepositoriesPage(accessToken, page, perPage, isInstallationToken);
            if (nextPage.isEmpty()) {
                break;
            }

            repositories.addAll(nextPage);
            if (nextPage.size() < perPage) {
                break;
            }

            page += 1;
        }

        return repositories;
    }

    private List<GithubRepositoryDTO> fetchRepositoriesPage(String accessToken, int page, int perPage, boolean isInstallationToken) {
        String uri = isInstallationToken ? "/installation/repositories" : "/user/repos";
        
        JsonNode response = fetchJsonNode(githubApiClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(uri)
                            .queryParam("page", page)
                            .queryParam("per_page", perPage);
                    if (!isInstallationToken) {
                        uriBuilder.queryParam("affiliation", "owner,collaborator,organization_member")
                                  .queryParam("visibility", "all");
                    }
                    return uriBuilder.build();
                })
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));

        List<GithubRepositoryDTO> repositories = new ArrayList<>();
        if (response != null) {
            JsonNode reposArray = isInstallationToken ? response.path("repositories") : response;
            if (reposArray.isArray()) {
                for (JsonNode repoNode : reposArray) {
                    repositories.add(mapRepositoryNode(repoNode));
                }
            }
        }
        return repositories;
    }

    private JsonNode fetchRepoNode(String accessToken, String owner, String repoName) {
        return fetchJsonNode(githubApiClient.get()
                .uri("/repos/{owner}/{repo}", owner, repoName)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
    }

    private JsonNode fetchUserNode(String accessToken) {
        return fetchJsonNode(githubApiClient.get()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
    }

    /**
     * Jackson's {@code asText(null)} turns JSON null into the literal string
     * {@code "null"}, which made job-conclusion filters miss real failed jobs.
     */
    static String jsonText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.asText("");
    }

    public JsonNode fetchJsonNode(WebClient.RequestHeadersSpec<?> request) {
        try {
            return request.retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException exception) {
            throw mapGithubException(exception, "GitHub API request failed");
        } catch (GithubApiException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("GitHub request failed", exception);
            throw new GithubApiException(HttpStatus.BAD_GATEWAY, "Unable to communicate with GitHub");
        }
    }

    private GithubApiException mapGithubException(WebClientResponseException exception, String defaultMessage) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String message = extractGithubErrorMessage(exception.getResponseBodyAsString(), defaultMessage);

        if (status == HttpStatus.UNAUTHORIZED) {
            return new GithubApiException(HttpStatus.UNAUTHORIZED, message != null ? message : "GitHub token is invalid or expired");
        }
        if (status == HttpStatus.FORBIDDEN) {
            return new GithubApiException(HttpStatus.FORBIDDEN, message != null ? message : "GitHub access is forbidden or rate-limited");
        }
        if (status == HttpStatus.NOT_FOUND) {
            return new GithubApiException(HttpStatus.NOT_FOUND, message != null ? message : "GitHub resource not found");
        }

        return new GithubApiException(status.isError() ? status : HttpStatus.BAD_GATEWAY,
                message != null ? message : defaultMessage);
    }

    private String extractGithubErrorMessage(String responseBody, String fallback) {
        if (responseBody == null || responseBody.isBlank()) {
            return fallback;
        }

        try {
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            if (jsonNode.has("message") && !jsonNode.get("message").isNull()) {
                String message = jsonNode.get("message").asText();
                return message.isBlank() ? fallback : message;
            }
        } catch (Exception ignored) {
            return responseBody;
        }

        return fallback;
    }

    private String requireAccessToken(User user) {
        if (user == null || user.getAccessToken() == null || user.getAccessToken().isBlank()) {
            throw new GithubApiException(HttpStatus.UNAUTHORIZED, "GitHub access token is missing for the authenticated user");
        }
        return user.getAccessToken();
    }

    private int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private int normalizePerPage(Integer perPage) {
        if (perPage == null || perPage < 1) {
            return MAX_PER_PAGE;
        }
        return Math.min(perPage, MAX_PER_PAGE);
    }

    public String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private GithubRepositoryDTO mapRepositoryNode(JsonNode repo) {
        return new GithubRepositoryDTO(
                repo.path("id").asLong(),
                repo.path("name").asText(""),
                repo.path("full_name").asText(""),
                repo.path("private").asBoolean(false),
                repo.path("html_url").asText(""),
                repo.path("default_branch").asText("main"),
                repo.path("description").isNull() ? "" : repo.path("description").asText(""),
                new GithubRepositoryOwnerDTO(repo.path("owner").path("login").asText(""))
        );
    }

    private Map<String, Object> toLegacyRepoMap(GithubRepositoryDTO repository) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", repository.id());
        map.put("name", repository.name());
        map.put("full_name", repository.fullName());
        map.put("private", repository.privateRepository());
        map.put("html_url", repository.htmlUrl());
        map.put("default_branch", repository.defaultBranch());
        map.put("description", repository.description());
        map.put("owner", repository.owner() != null ? repository.owner().login() : "");
        map.put("owner_login", repository.owner() != null ? repository.owner().login() : "");
        return map;
    }

    private Map<String, Object> mapRepoNode(JsonNode repo) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", repo.path("id").asLong());
        map.put("name", repo.path("name").asText(""));
        map.put("full_name", repo.path("full_name").asText(""));
        map.put("owner", repo.path("owner").path("login").asText(""));
        map.put("description", repo.path("description").isNull() ? "" : repo.path("description").asText(""));
        map.put("html_url", repo.path("html_url").asText(""));
        map.put("clone_url", repo.path("clone_url").asText(""));
        map.put("language", repo.path("language").isNull() ? "" : repo.path("language").asText(""));
        map.put("stargazers_count", repo.path("stargazers_count").asInt());
        map.put("forks_count", repo.path("forks_count").asInt());
        map.put("private", repo.path("private").asBoolean(false));
        map.put("default_branch", repo.path("default_branch").asText("main"));
        map.put("updated_at", repo.path("updated_at").asText(""));
        map.put("created_at", repo.path("created_at").asText(""));
        if (repo.has("topics") && repo.get("topics").isArray()) {
            List<String> topics = new ArrayList<>();
            repo.get("topics").forEach(topic -> topics.add(topic.asText()));
            map.put("topics", topics);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private void insertTreeEntry(Map<String, Object> root, String path, String type, JsonNode entry) {
        List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");
        String[] segments = path.split("/");
        StringBuilder currentPath = new StringBuilder();
        List<Map<String, Object>> currentChildren = children;
        Map<String, Object> currentNode = root;

        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (currentPath.length() > 0) {
                currentPath.append('/');
            }
            currentPath.append(segment);

            boolean lastSegment = index == segments.length - 1;
            String nodeType = lastSegment ? ("tree".equals(type) ? "dir" : "file") : "dir";
            Map<String, Object> nextNode = findChild(currentChildren, segment);
            if (nextNode == null) {
                nextNode = new LinkedHashMap<>();
                nextNode.put("name", segment);
                nextNode.put("path", currentPath.toString());
                nextNode.put("type", nodeType);
                if ("dir".equals(nodeType)) {
                    nextNode.put("children", new ArrayList<Map<String, Object>>());
                } else {
                    nextNode.put("size", entry.path("size").asLong(0));
                }
                currentChildren.add(nextNode);
            }

            currentNode = nextNode;
            if ("dir".equals(currentNode.get("type"))) {
                currentChildren = (List<Map<String, Object>>) currentNode.get("children");
            }
        }
    }

    private Map<String, Object> findChild(List<Map<String, Object>> children, String name) {
        for (Map<String, Object> child : children) {
            if (name.equals(child.get("name"))) {
                return child;
            }
        }
        return null;
    }

    public String fetchPullRequestDiff(String token, String owner, String repo, int pullNumber) {
        try {
            return this.githubApiClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{pullNumber}", owner, repo, pullNumber)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch PR diff for {}/{} #{}", owner, repo, pullNumber, e);
            throw new GithubApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch PR diff: " + e.getMessage());
        }
    }

    public void postPullRequestComment(String token, String owner, String repo, int pullNumber, String commentBody) {
        try {
            this.githubApiClient.post()
                    .uri("/repos/{owner}/{repo}/issues/{pullNumber}/comments", owner, repo, pullNumber)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("body", commentBody))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
            log.info("Posted AI review comment to {}/{} #{}", owner, repo, pullNumber);
        } catch (Exception e) {
            log.error("Failed to post PR comment to {}/{} #{}", owner, repo, pullNumber, e);
        }
    }
}

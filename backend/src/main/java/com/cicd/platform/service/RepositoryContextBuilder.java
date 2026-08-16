package com.cicd.platform.service;

import com.cicd.platform.dto.*;
import com.cicd.platform.model.User;
import com.cicd.platform.util.FileTypeUtil;
import com.cicd.platform.util.SecretDetectorUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class RepositoryContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(RepositoryContextBuilder.class);

    private final GitHubService gitHubService;

    @Value("${app.repository-context.max-payload-bytes:5242880}")
    private long maxPayloadBytes;

    @Value("${app.repository-context.include-secrets:false}")
    private boolean includeSecrets;

    public RepositoryContextBuilder(GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    /**
     * Builds the complete in-memory RepositoryContext directly from GitHub for the Pipeline Generation Agent.
     */
    public RepositoryContext buildRepositoryContext(User user, String owner, String repoName, String branch) {
        log.info("Building RepositoryContext for {}/{} (requested branch: {})", owner, repoName, branch);

        // 1. Get repository details for default branch & GitHub repo ID
        Map<String, Object> details = gitHubService.getRepoDetails(user, owner, repoName);
        Long githubRepoId = details.containsKey("id") ? ((Number) details.get("id")).longValue() : 0L;
        String targetBranch = branch != null && !branch.isBlank()
                ? branch
                : (String) details.getOrDefault("default_branch", "main");

        // 2. Fetch branch commit SHA and tree SHA via GitHubService
        JsonNode branchResponse = gitHubService.getBranchInfo(user, owner, repoName, targetBranch);

        if (branchResponse == null || !branchResponse.has("commit")) {
            throw new RuntimeException("Branch '" + targetBranch + "' not found for repository " + owner + "/" + repoName);
        }

        String commitSha = branchResponse.path("commit").path("sha").asText("");
        String treeSha = branchResponse.path("commit").path("commit").path("tree").path("sha").asText("");

        if (commitSha.isBlank() || treeSha.isBlank()) {
            throw new RuntimeException("Unable to resolve commit or tree SHA for " + owner + "/" + repoName);
        }

        RepositoryInfo repositoryInfo = new RepositoryInfo(githubRepoId, owner, repoName, targetBranch, commitSha);

        // 3. Fetch recursive tree from GitHub API via GitHubService
        JsonNode treeResponse = gitHubService.getRecursiveTree(user, owner, repoName, treeSha);

        List<RepositoryTreeNode> structureList = new ArrayList<>();
        List<RepositoryFileContent> filesList = new ArrayList<>();

        long totalBytesAccumulated = 0L;
        boolean isTruncated = false;
        String limitMessage = null;

        if (treeResponse != null && treeResponse.has("tree") && treeResponse.get("tree").isArray()) {
            for (JsonNode entry : treeResponse.get("tree")) {
                String path = entry.path("path").asText("");
                String type = entry.path("type").asText(""); // "tree" or "blob"
                String sha = entry.path("sha").asText("");
                long size = entry.path("size").asLong(0);

                if (path.isBlank()) {
                    continue;
                }

                String name = extractNameFromPath(path);

                if ("tree".equals(type)) {
                    structureList.add(new RepositoryTreeNode(path, name, "directory"));
                } else if ("blob".equals(type)) {
                    boolean isBinary = FileTypeUtil.isBinaryFile(path);
                    boolean isSecret = SecretDetectorUtil.isSecretFile(path);
                    String nodeType = isBinary ? "binary" : "text";

                    structureList.add(new RepositoryTreeNode(path, name, nodeType));

                    RepositoryFileContent fileContent = new RepositoryFileContent();
                    fileContent.setPath(path);
                    fileContent.setName(name);
                    fileContent.setType(nodeType);
                    fileContent.setSize(size);
                    fileContent.setSha(sha);
                    fileContent.setSecret(isSecret);

                    if (isBinary) {
                        fileContent.setContent(null);
                        filesList.add(fileContent);
                    } else {
                        // Check payload size limit before fetching text contents
                        if (totalBytesAccumulated + size > maxPayloadBytes) {
                            isTruncated = true;
                            limitMessage = "Payload size limit (" + (maxPayloadBytes / (1024 * 1024)) + " MB) exceeded at path: " + path;
                            log.warn("RepositoryContext payload threshold reached for {}/{}. Truncating remaining file contents.", owner, repoName);
                            filesList.add(fileContent);
                            break;
                        }

                        try {
                            Map<String, Object> contentMap = gitHubService.getFileContent(user, owner, repoName, path, targetBranch);
                            String rawContent = contentMap != null ? (String) contentMap.get("content") : "";
                            String finalContent = SecretDetectorUtil.sanitizeContent(path, rawContent, includeSecrets);

                            fileContent.setContent(finalContent);
                            long contentByteSize = finalContent != null ? finalContent.getBytes(StandardCharsets.UTF_8).length : 0;
                            totalBytesAccumulated += contentByteSize;

                        } catch (Exception e) {
                            log.warn("Could not retrieve file content for path {}: {}", path, e.getMessage());
                            fileContent.setContent(null);
                        }

                        filesList.add(fileContent);
                    }
                }
            }
        }

        RepositoryContext context = new RepositoryContext();
        context.setRepository(repositoryInfo);
        context.setStructure(structureList);
        context.setFiles(filesList);
        context.setTotalBytes(totalBytesAccumulated);
        context.setTruncated(isTruncated);
        context.setMessage(limitMessage != null ? limitMessage : "Complete repository context successfully constructed from GitHub.");

        log.info("Built RepositoryContext for {}/{} (Commit SHA: {}, Total Nodes: {}, Text Files: {}, Total Bytes: {})",
                owner, repoName, commitSha, structureList.size(), filesList.size(), totalBytesAccumulated);

        return context;
    }

    private String extractNameFromPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}

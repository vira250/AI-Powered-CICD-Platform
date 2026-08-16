package com.cicd.platform.service;

import com.cicd.platform.model.ImportedRepo;
import com.cicd.platform.model.RepoFile;
import com.cicd.platform.model.User;
import com.cicd.platform.repository.RepoFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class RepoSyncService {

    private static final Logger log = LoggerFactory.getLogger(RepoSyncService.class);

    private final RepoFileRepository repoFileRepository;
    private final GitHubService gitHubService;

    public RepoSyncService(RepoFileRepository repoFileRepository, GitHubService gitHubService) {
        this.repoFileRepository = repoFileRepository;
        this.gitHubService = gitHubService;
    }

    /**
     * Synchronize and store repository folder structure and files into PostgreSQL.
     */
    @Transactional
    public void syncRepoStructureAndFiles(User user, ImportedRepo importedRepo) {
        try {
            log.info("Starting PostgreSQL sync for repository {}/{}", importedRepo.getOwner(), importedRepo.getName());

            Map<String, Object> structure = gitHubService.getRepoStructure(
                    user,
                    importedRepo.getOwner(),
                    importedRepo.getName(),
                    importedRepo.getDefaultBranch()
            );

            if (structure == null || !structure.containsKey("tree")) {
                log.warn("No tree returned from GitHub for {}", importedRepo.getFullName());
                return;
            }

            // Remove existing synced files for this repo
            repoFileRepository.deleteByImportedRepoId(importedRepo.getId());

            @SuppressWarnings("unchecked")
            Map<String, Object> rootTree = (Map<String, Object>) structure.get("tree");
            List<RepoFile> entitiesToSave = new ArrayList<>();

            flattenAndCollectNodes(rootTree, importedRepo.getId(), importedRepo.getUserId(), importedRepo.getDefaultBranch(), "", entitiesToSave);

            repoFileRepository.saveAll(entitiesToSave);
            log.info("Saved {} folder & file structure entries to PostgreSQL for {}", entitiesToSave.size(), importedRepo.getFullName());

            // Fetch and store contents for files in PostgreSQL
            syncFileContents(user, importedRepo, entitiesToSave);

        } catch (Exception e) {
            log.error("Failed to sync repository structure to PostgreSQL for {}", importedRepo.getFullName(), e);
        }
    }

    private void syncFileContents(User user, ImportedRepo importedRepo, List<RepoFile> files) {
        int count = 0;
        for (RepoFile file : files) {
            if ("file".equalsIgnoreCase(file.getType()) && (file.getSize() == null || file.getSize() < 500000)) {
                try {
                    Map<String, Object> contentData = gitHubService.getFileContent(
                            user,
                            importedRepo.getOwner(),
                            importedRepo.getName(),
                            file.getPath(),
                            file.getBranch()
                    );

                    if (contentData != null && contentData.containsKey("content")) {
                        file.setContent((String) contentData.get("content"));
                        repoFileRepository.save(file);
                        count++;
                    }
                } catch (Exception e) {
                    log.debug("Skipping content sync for path {}: {}", file.getPath(), e.getMessage());
                }
            }
        }
        log.info("Successfully populated source contents for {} files in PostgreSQL for {}", count, importedRepo.getFullName());
    }

    @SuppressWarnings("unchecked")
    private void flattenAndCollectNodes(Map<String, Object> node, Long importedRepoId, Long userId, String branch, String currentPath, List<RepoFile> result) {
        if (node == null) return;

        String name = (String) node.getOrDefault("name", "");
        String type = (String) node.getOrDefault("type", "dir");
        String nodePath = node.containsKey("path") ? (String) node.get("path") : null;

        // The root node from getRepoStructure has path="" — skip creating an entity for it
        boolean isRoot = (nodePath != null && nodePath.isEmpty()) || currentPath.isEmpty() && "dir".equalsIgnoreCase(type) && !node.containsKey("size");

        if (!isRoot && !name.isEmpty()) {
            RepoFile entity = new RepoFile();
            entity.setImportedRepoId(importedRepoId);
            entity.setUserId(userId);
            entity.setPath(currentPath);
            entity.setName(name);
            entity.setType("dir".equalsIgnoreCase(type) || "tree".equalsIgnoreCase(type) ? "dir" : "file");
            if (node.containsKey("size") && node.get("size") instanceof Number num) {
                entity.setSize(num.longValue());
            }
            entity.setBranch(branch);
            result.add(entity);
        }

        if (node.containsKey("children") && node.get("children") instanceof List<?> childrenList) {
            for (Object childObj : childrenList) {
                if (childObj instanceof Map<?, ?> childMap) {
                    String childName = (String) childMap.get("name");
                    // For root node children, path is just the child name (no prefix)
                    String childPath;
                    if (isRoot) {
                        childPath = childName;
                    } else {
                        childPath = currentPath.isEmpty() ? childName : currentPath + "/" + childName;
                    }
                    flattenAndCollectNodes((Map<String, Object>) childMap, importedRepoId, userId, branch, childPath, result);
                }
            }
        }
    }

    /**
     * Build tree JSON structure directly from PostgreSQL database.
     */
    public Map<String, Object> buildStructureFromDatabase(Long importedRepoId, String branch) {
        List<RepoFile> files = repoFileRepository.findByImportedRepoId(importedRepoId);
        if (files.isEmpty()) {
            return null;
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "root");
        root.put("type", "dir");
        root.put("children", new ArrayList<Map<String, Object>>());

        int[] counts = new int[]{0, 0}; // [folders, files]

        for (RepoFile file : files) {
            insertTreeEntry(root, file, counts);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", branch != null ? branch : "main");
        result.put("folderCount", counts[0]);
        result.put("fileCount", counts[1]);
        result.put("tree", root);
        result.put("source", "postgresql");
        return result;
    }

    @SuppressWarnings("unchecked")
    private void insertTreeEntry(Map<String, Object> root, RepoFile file, int[] counts) {
        List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");
        String[] segments = file.getPath().split("/");
        List<Map<String, Object>> currentChildren = children;

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean isLeaf = (i == segments.length - 1);

            Map<String, Object> existing = findChild(currentChildren, segment);
            if (existing == null) {
                Map<String, Object> newNode = new LinkedHashMap<>();
                newNode.put("name", segment);
                newNode.put("path", isLeaf ? file.getPath() : String.join("/", Arrays.copyOfRange(segments, 0, i + 1)));

                if (isLeaf) {
                    newNode.put("type", file.getType());
                    if ("file".equalsIgnoreCase(file.getType())) {
                        newNode.put("size", file.getSize() != null ? file.getSize() : 0);
                        counts[1]++;
                    } else {
                        newNode.put("children", new ArrayList<Map<String, Object>>());
                        counts[0]++;
                    }
                } else {
                    newNode.put("type", "dir");
                    newNode.put("children", new ArrayList<Map<String, Object>>());
                    counts[0]++;
                }

                currentChildren.add(newNode);
                existing = newNode;
            }

            if (existing.containsKey("children")) {
                currentChildren = (List<Map<String, Object>>) existing.get("children");
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
}

package com.cicd.platform.dto;

import java.util.ArrayList;
import java.util.List;

public class RepositoryContext {

    private RepositoryInfo repository;
    private List<RepositoryTreeNode> structure = new ArrayList<>();
    private List<RepositoryFileContent> files = new ArrayList<>();

    private Long totalBytes = 0L;
    private boolean truncated = false;
    private String message;

    public RepositoryContext() {
    }

    public RepositoryContext(RepositoryInfo repository, List<RepositoryTreeNode> structure, List<RepositoryFileContent> files) {
        this.repository = repository;
        this.structure = structure != null ? structure : new ArrayList<>();
        this.files = files != null ? files : new ArrayList<>();
    }

    public RepositoryInfo getRepository() {
        return repository;
    }

    public void setRepository(RepositoryInfo repository) {
        this.repository = repository;
    }

    public List<RepositoryTreeNode> getStructure() {
        return structure;
    }

    public void setStructure(List<RepositoryTreeNode> structure) {
        this.structure = structure;
    }

    public List<RepositoryFileContent> getFiles() {
        return files;
    }

    public void setFiles(List<RepositoryFileContent> files) {
        this.files = files;
    }

    public Long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(Long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

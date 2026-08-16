package com.cicd.platform.dto;

public class RepositoryFileContent {

    private String path;
    private String name;
    private String type; // "text" or "binary"
    private Long size;
    private String sha;
    private String content; // text content, or null for binary files
    private boolean isSecret;

    public RepositoryFileContent() {
    }

    public RepositoryFileContent(String path, String name, String type, Long size, String sha, String content, boolean isSecret) {
        this.path = path;
        this.name = name;
        this.type = type;
        this.size = size;
        this.sha = sha;
        this.content = content;
        this.isSecret = isSecret;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getSha() {
        return sha;
    }

    public void setSha(String sha) {
        this.sha = sha;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isSecret() {
        return isSecret;
    }

    public void setSecret(boolean secret) {
        isSecret = secret;
    }
}

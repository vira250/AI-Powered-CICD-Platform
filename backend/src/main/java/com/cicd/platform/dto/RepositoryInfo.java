package com.cicd.platform.dto;

public class RepositoryInfo {

    private Long githubRepositoryId;
    private String owner;
    private String repositoryName;
    private String branch;
    private String commitSha;

    public RepositoryInfo() {
    }

    public RepositoryInfo(Long githubRepositoryId, String owner, String repositoryName, String branch, String commitSha) {
        this.githubRepositoryId = githubRepositoryId;
        this.owner = owner;
        this.repositoryName = repositoryName;
        this.branch = branch;
        this.commitSha = commitSha;
    }

    public Long getGithubRepositoryId() {
        return githubRepositoryId;
    }

    public void setGithubRepositoryId(Long githubRepositoryId) {
        this.githubRepositoryId = githubRepositoryId;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }
}

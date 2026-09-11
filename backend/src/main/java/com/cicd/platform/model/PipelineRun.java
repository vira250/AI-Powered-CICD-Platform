package com.cicd.platform.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline_run")
public class PipelineRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String repoName;

    private String branch;
    private String commitSha;

    @Column(columnDefinition = "TEXT")
    private String yamlContent;

    private String filePath;

    @Column(columnDefinition = "TEXT")
    private String technologyJson;

    @Column(columnDefinition = "TEXT")
    private String validationJson;

    private String status; // pending, generating, success, warning, error
    private String message;
    private int generationTimeMs;

    private boolean pushedToGithub;
    private String pushCommitSha;

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getRepoName() { return repoName; }
    public void setRepoName(String repoName) { this.repoName = repoName; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }

    public String getYamlContent() { return yamlContent; }
    public void setYamlContent(String yamlContent) { this.yamlContent = yamlContent; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getTechnologyJson() { return technologyJson; }
    public void setTechnologyJson(String technologyJson) { this.technologyJson = technologyJson; }

    public String getValidationJson() { return validationJson; }
    public void setValidationJson(String validationJson) { this.validationJson = validationJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public int getGenerationTimeMs() { return generationTimeMs; }
    public void setGenerationTimeMs(int generationTimeMs) { this.generationTimeMs = generationTimeMs; }

    public boolean isPushedToGithub() { return pushedToGithub; }
    public void setPushedToGithub(boolean pushedToGithub) { this.pushedToGithub = pushedToGithub; }

    public String getPushCommitSha() { return pushCommitSha; }
    public void setPushCommitSha(String pushCommitSha) { this.pushCommitSha = pushCommitSha; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}

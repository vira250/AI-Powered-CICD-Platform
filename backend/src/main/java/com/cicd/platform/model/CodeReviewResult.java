package com.cicd.platform.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "code_review_result")
public class CodeReviewResult {

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
    private String reviewId;

    @Column(columnDefinition = "TEXT")
    private String summary;

    private String verdict; // approve, request_changes, comment

    @Column(columnDefinition = "TEXT")
    private String findingsJson;

    private int filesReviewed;
    private int filesSkipped;
    private int findingsCount;

    private String status; // pending, analyzing, success, error
    private int generationTimeMs;

    private LocalDateTime createdAt;

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

    public String getReviewId() { return reviewId; }
    public void setReviewId(String reviewId) { this.reviewId = reviewId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }

    public String getFindingsJson() { return findingsJson; }
    public void setFindingsJson(String findingsJson) { this.findingsJson = findingsJson; }

    public int getFilesReviewed() { return filesReviewed; }
    public void setFilesReviewed(int filesReviewed) { this.filesReviewed = filesReviewed; }

    public int getFilesSkipped() { return filesSkipped; }
    public void setFilesSkipped(int filesSkipped) { this.filesSkipped = filesSkipped; }

    public int getFindingsCount() { return findingsCount; }
    public void setFindingsCount(int findingsCount) { this.findingsCount = findingsCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getGenerationTimeMs() { return generationTimeMs; }
    public void setGenerationTimeMs(int generationTimeMs) { this.generationTimeMs = generationTimeMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

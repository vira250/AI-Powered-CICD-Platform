package com.cicd.platform.repository;

import com.cicd.platform.model.LogAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface LogAnalysisRepository extends JpaRepository<LogAnalysis, Long> {
    Optional<LogAnalysis> findTopByOwnerAndRepoNameAndWorkflowRunIdAndJobIdOrderByCreatedAtDesc(
            String owner, String repoName, Long workflowRunId, Long jobId);
}
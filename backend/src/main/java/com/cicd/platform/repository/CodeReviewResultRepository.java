package com.cicd.platform.repository;

import com.cicd.platform.model.CodeReviewResult;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CodeReviewResultRepository extends JpaRepository<CodeReviewResult, Long> {
    List<CodeReviewResult> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<CodeReviewResult> findByUserIdAndOwnerAndRepoNameOrderByCreatedAtDesc(Long userId, String owner, String repoName);
}

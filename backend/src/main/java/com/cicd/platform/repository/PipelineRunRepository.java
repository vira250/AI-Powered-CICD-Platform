package com.cicd.platform.repository;

import com.cicd.platform.model.PipelineRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long> {
    List<PipelineRun> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<PipelineRun> findByUserIdAndOwnerAndRepoNameOrderByCreatedAtDesc(Long userId, String owner, String repoName);
    Optional<PipelineRun> findByIdAndUserId(Long id, Long userId);
}

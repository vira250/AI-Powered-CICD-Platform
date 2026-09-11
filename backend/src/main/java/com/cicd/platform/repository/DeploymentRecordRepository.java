package com.cicd.platform.repository;

import com.cicd.platform.model.DeploymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeploymentRecordRepository extends JpaRepository<DeploymentRecord, Long> {
    List<DeploymentRecord> findByOwnerAndRepoNameOrderByCreatedAtDesc(String owner, String repoName);
    List<DeploymentRecord> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<DeploymentRecord> findByUserIdAndOwnerAndRepoNameOrderByCreatedAtDesc(Long userId, String owner, String repoName);
}

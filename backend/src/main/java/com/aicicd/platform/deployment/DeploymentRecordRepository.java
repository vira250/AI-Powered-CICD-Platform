package com.aicicd.platform.deployment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeploymentRecordRepository extends JpaRepository<DeploymentRecord, Long> {
    List<DeploymentRecord> findByRepositoryIdOrderByDeployedAtDesc(Long repositoryId);
    Optional<DeploymentRecord> findByRepositoryIdAndCurrentTrue(Long repositoryId);
}

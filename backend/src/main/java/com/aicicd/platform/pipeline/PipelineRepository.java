package com.aicicd.platform.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PipelineRepository extends JpaRepository<PipelineEntity, Long> {
    List<PipelineEntity> findByRepositoryIdOrderByCreatedAtDesc(Long repositoryId);
}

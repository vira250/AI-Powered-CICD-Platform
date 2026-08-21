package com.aicicd.platform.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {
    List<AnalysisReport> findByRepositoryIdOrderByCreatedAtDesc(Long repositoryId);
    void deleteByRepositoryId(Long repositoryId);
}

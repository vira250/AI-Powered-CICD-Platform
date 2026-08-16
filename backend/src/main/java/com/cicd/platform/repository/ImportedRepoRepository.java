package com.cicd.platform.repository;

import com.cicd.platform.model.ImportedRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImportedRepoRepository extends JpaRepository<ImportedRepo, Long> {
    List<ImportedRepo> findByUserId(Long userId);
    Optional<ImportedRepo> findByUserIdAndGithubRepoId(Long userId, Long githubRepoId);
    boolean existsByUserIdAndGithubRepoId(Long userId, Long githubRepoId);
}

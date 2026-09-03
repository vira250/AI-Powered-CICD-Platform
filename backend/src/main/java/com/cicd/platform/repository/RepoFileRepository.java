package com.cicd.platform.repository;

import com.cicd.platform.model.RepoFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepoFileRepository extends JpaRepository<RepoFile, Long> {
    List<RepoFile> findByImportedRepoId(Long importedRepoId);
    Optional<RepoFile> findByImportedRepoIdAndPath(Long importedRepoId, String path);
    void deleteByImportedRepoId(Long importedRepoId);
    boolean existsByImportedRepoId(Long importedRepoId);
}

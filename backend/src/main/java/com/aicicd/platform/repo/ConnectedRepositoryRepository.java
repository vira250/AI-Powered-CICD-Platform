package com.aicicd.platform.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConnectedRepositoryRepository extends JpaRepository<ConnectedRepository, Long> {
    Optional<ConnectedRepository> findByFullName(String fullName);
}

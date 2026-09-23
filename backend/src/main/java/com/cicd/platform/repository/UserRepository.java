package com.cicd.platform.repository;

import com.cicd.platform.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByGithubId(Long githubId);
    Optional<User> findByUsername(String username);
    Optional<User> findBySessionToken(String sessionToken);
    Optional<User> findByEmail(String email);
    Optional<User> findByGoogleId(String googleId);
}


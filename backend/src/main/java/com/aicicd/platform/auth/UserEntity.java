package com.aicicd.platform.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = true)
    private Long githubId;

    @Column(unique = true, nullable = true)
    private String login;

    @Column(unique = true, nullable = true)
    private String email;

    private String password; // hashed

    private String name;
    private String avatarUrl;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}

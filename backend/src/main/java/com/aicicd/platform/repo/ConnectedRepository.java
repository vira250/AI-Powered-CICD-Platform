package com.aicicd.platform.repo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "connected_repositories")
@Getter
@Setter
public class ConnectedRepository {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long installationId;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String fullName;           // owner/name

    private String defaultBranch = "main";
    private Boolean isPrivate = false;

    @Column(nullable = false)
    private Instant connectedAt = Instant.now();
}

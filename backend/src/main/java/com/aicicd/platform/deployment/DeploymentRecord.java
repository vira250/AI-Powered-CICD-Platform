package com.aicicd.platform.deployment;

import com.aicicd.platform.repo.ConnectedRepository;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One row per deployed image version — the platform's version history. */
@Entity
@Table(name = "deployments")
@Getter
@Setter
public class DeploymentRecord {

    public enum Status {DEPLOYED, ROLLED_BACK, FAILED, UNHEALTHY}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    private ConnectedRepository repository;

    @Column(nullable = false)
    private String version;

    private String image;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private boolean current;

    @Column(columnDefinition = "TEXT")
    private String detailJson;

    @Column(nullable = false)
    private Instant deployedAt = Instant.now();
}

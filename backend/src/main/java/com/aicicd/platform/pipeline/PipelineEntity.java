package com.aicicd.platform.pipeline;

import com.aicicd.platform.repo.ConnectedRepository;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "pipelines")
@Getter
@Setter
public class PipelineEntity {

    public enum Status {GENERATED, PUSHED, RUNNING, SUCCESS, FAILED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    private ConnectedRepository repository;

    private String workflowPath;
    private String templateUsed;

    @Column(columnDefinition = "TEXT")
    private String stackJson;

    @Column(columnDefinition = "TEXT")
    private String workflowYaml;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.GENERATED;

    private Long lastRunId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant pushedAt;
}

package com.aicicd.platform.pipeline;

import com.aicicd.platform.repo.ConnectedRepository;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Output of the Log Analysis / Code Review agents, shown on the dashboard. */
@Entity
@Table(name = "analysis_reports")
@Getter
@Setter
public class AnalysisReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    private ConnectedRepository repository;

    private Long runId;
    private String agent;

    @Column(columnDefinition = "TEXT")
    private String rootCause;

    @Column(columnDefinition = "TEXT")
    private String impact;

    @Column(columnDefinition = "TEXT")
    private String suggestedFix;

    private Integer confidence;
    private Boolean simpleFix;

    @Column(columnDefinition = "TEXT")
    private String errorExcerpt;

    @Column(columnDefinition = "TEXT")
    private String reviewReportJson;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}

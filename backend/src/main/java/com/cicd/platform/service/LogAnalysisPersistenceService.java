package com.cicd.platform.service;

import com.cicd.platform.model.LogAnalysis;
import com.cicd.platform.repository.LogAnalysisRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Optional;

@Service
public class LogAnalysisPersistenceService {
    private final LogAnalysisRepository repository;
    private final ObjectMapper objectMapper;

    public LogAnalysisPersistenceService(LogAnalysisRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void save(String owner, String repo, long runId, long jobId, Map<String, Object> analysis) {
        try {
            LogAnalysis entity = new LogAnalysis();
            entity.setOwner(owner);
            entity.setRepoName(repo);
            entity.setWorkflowRunId(runId);
            entity.setJobId(jobId);
            entity.setAnalysisJson(objectMapper.writeValueAsString(analysis));
            repository.save(entity);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize log analysis", exception);
        }
    }

    public Optional<Map<String, Object>> findLatest(String owner, String repo, long runId, long jobId) {
        return repository.findTopByOwnerAndRepoNameAndWorkflowRunIdAndJobIdOrderByCreatedAtDesc(owner, repo, runId, jobId)
                .map(LogAnalysis::getAnalysisJson)
                .map(this::readAnalysis);
    }

    private Map<String, Object> readAnalysis(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read stored log analysis", exception);
        }
    }
}
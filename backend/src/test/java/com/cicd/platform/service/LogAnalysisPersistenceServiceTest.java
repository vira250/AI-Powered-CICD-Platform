package com.cicd.platform.service;

import com.cicd.platform.model.LogAnalysis;
import com.cicd.platform.repository.LogAnalysisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LogAnalysisPersistenceServiceTest {

    @Mock
    private LogAnalysisRepository repository;

    @Test
    void savesAnalysisWithWorkflowIdentityAndStructuredJson() {
        LogAnalysisPersistenceService service = new LogAnalysisPersistenceService(repository, new ObjectMapper());

        service.save("owner", "repo", 123L, 456L, Map.of("error_type", "COMPILATION_ERROR"));

        ArgumentCaptor<LogAnalysis> captor = ArgumentCaptor.forClass(LogAnalysis.class);
        verify(repository).save(captor.capture());
        LogAnalysis saved = captor.getValue();
        assertEquals("owner", saved.getOwner());
        assertEquals("repo", saved.getRepoName());
        assertEquals(123L, saved.getWorkflowRunId());
        assertEquals(456L, saved.getJobId());
        assertEquals("{\"error_type\":\"COMPILATION_ERROR\"}", saved.getAnalysisJson());
    }
}
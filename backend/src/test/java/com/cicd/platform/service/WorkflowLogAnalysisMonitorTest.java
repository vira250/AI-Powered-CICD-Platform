package com.cicd.platform.service;

import com.cicd.platform.model.PipelineRun;
import com.cicd.platform.model.User;
import com.cicd.platform.repository.PipelineRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowLogAnalysisMonitorTest {

    @Mock private GitHubService gitHubService;
    @Mock private LogAnalysisPersistenceService persistenceService;
    @Mock private PipelineRunRepository pipelineRunRepository;
    @Mock private AgentOrchestratorService orchestratorService;

    @Test
    void analyzesAndPersistsSuccessfulWorkflowJobsWithoutManualSelection() {
        PipelineRun pipelineRun = pipelineRun();
        TaskExecutor directExecutor = Runnable::run;
        WorkflowLogAnalysisMonitor monitor = new WorkflowLogAnalysisMonitor(
                directExecutor, gitHubService, persistenceService, pipelineRunRepository,
                orchestratorService, 1, 1);

        when(pipelineRunRepository.findById(7L)).thenReturn(Optional.of(pipelineRun));
        when(gitHubService.listWorkflowRuns(any(), eq("owner"), eq("repo"))).thenReturn(List.of(Map.of(
                "id", 70L, "head_sha", "generated-commit", "path", ".github/workflows/ci.yml",
                "status", "completed", "conclusion", "success")));
        when(gitHubService.listWorkflowJobs(any(), eq("owner"), eq("repo"), eq(70L))).thenReturn(List.of(
                Map.of("id", 701L, "name", "build", "status", "completed", "conclusion", "success"),
                Map.of("id", 702L, "name", "test", "status", "completed", "conclusion", "success")
        ));
        when(persistenceService.findLatest(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(gitHubService.getWorkflowJobLogs(any(), eq("owner"), eq("repo"), anyLong())).thenReturn("Build completed successfully.");
        when(orchestratorService.analyzeLogs(anyString())).thenReturn(Map.of("status", "passed", "error_type", "UNKNOWN_ERROR"));

        monitor.monitorGeneratedWorkflow(pipelineRun, new User(), "generated-commit", ".github/workflows/ci.yml");

        verify(orchestratorService, times(2)).analyzeLogs("Build completed successfully.");
        verify(persistenceService).save(eq("owner"), eq("repo"), eq(70L), eq(701L), any());
        verify(persistenceService).save(eq("owner"), eq("repo"), eq(70L), eq(702L), any());
        assertEquals("completed", pipelineRun.getLogAnalysisStatus());
        assertEquals(701L, pipelineRun.getLogAnalysisJobId());
    }

    @Test
    void analyzesOnlyFailedJobsForFailedWorkflow() {
        PipelineRun pipelineRun = pipelineRun();
        TaskExecutor directExecutor = Runnable::run;
        WorkflowLogAnalysisMonitor monitor = new WorkflowLogAnalysisMonitor(
                directExecutor, gitHubService, persistenceService, pipelineRunRepository,
                orchestratorService, 1, 1);

        when(pipelineRunRepository.findById(7L)).thenReturn(Optional.of(pipelineRun));
        when(gitHubService.listWorkflowRuns(any(), eq("owner"), eq("repo"))).thenReturn(List.of(Map.of(
                "id", 70L, "head_sha", "generated-commit", "path", ".github/workflows/ci.yml@refs/heads/main",
                "status", "completed", "conclusion", "failure")));
        when(gitHubService.listWorkflowJobs(any(), eq("owner"), eq("repo"), eq(70L))).thenReturn(List.of(
                Map.of("id", 701L, "name", "build", "status", "completed", "conclusion", "success"),
                Map.of("id", 702L, "name", "test", "status", "completed", "conclusion", "failure")
        ));
        when(persistenceService.findLatest(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(gitHubService.getWorkflowJobLogs(any(), eq("owner"), eq("repo"), eq(702L))).thenReturn("error: test failed");
        when(orchestratorService.analyzeLogs("error: test failed")).thenReturn(Map.of("status", "failed", "error_type", "TEST_FAILURE"));

        monitor.monitorGeneratedWorkflow(pipelineRun, new User(), "generated-commit", ".github/workflows/ci.yml");

        verify(orchestratorService).analyzeLogs("error: test failed");
        verify(gitHubService, never()).getWorkflowJobLogs(any(), anyString(), anyString(), eq(701L));
        verify(persistenceService).save(eq("owner"), eq("repo"), eq(70L), eq(702L), any());
        assertEquals("completed", pipelineRun.getLogAnalysisStatus());
        assertEquals(702L, pipelineRun.getLogAnalysisJobId());
    }

    @Test
    void analyzesFailedWorkflowWhenJobConclusionIsTimedOut() {
        PipelineRun pipelineRun = pipelineRun();
        WorkflowLogAnalysisMonitor monitor = monitor();

        when(pipelineRunRepository.findById(7L)).thenReturn(Optional.of(pipelineRun));
        when(gitHubService.listWorkflowRuns(any(), eq("owner"), eq("repo"))).thenReturn(List.of(Map.of(
                "id", 70L, "head_sha", "generated-commit", "path", ".github/workflows/ci.yml",
                "status", "completed", "conclusion", "failure")));
        when(gitHubService.listWorkflowJobs(any(), eq("owner"), eq("repo"), eq(70L))).thenReturn(List.of(
                Map.of("id", 702L, "name", "test", "status", "completed", "conclusion", "timed_out")
        ));
        when(persistenceService.findLatest(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(gitHubService.getWorkflowJobLogs(any(), eq("owner"), eq("repo"), eq(702L))).thenReturn("error: timed out");
        when(orchestratorService.analyzeLogs("error: timed out")).thenReturn(Map.of("status", "failed", "error_type", "UNKNOWN_ERROR"));

        monitor.monitorGeneratedWorkflow(pipelineRun, new User(), "generated-commit", ".github/workflows/ci.yml");

        verify(persistenceService).save(eq("owner"), eq("repo"), eq(70L), eq(702L), any());
        assertEquals("completed", pipelineRun.getLogAnalysisStatus());
    }

    @Test
    void analyzesFailedJobEvenWhenGitHubHasNotMarkedStatusCompleted() {
        PipelineRun pipelineRun = pipelineRun();
        WorkflowLogAnalysisMonitor monitor = monitor();

        when(pipelineRunRepository.findById(7L)).thenReturn(Optional.of(pipelineRun));
        when(gitHubService.listWorkflowRuns(any(), eq("owner"), eq("repo"))).thenReturn(List.of(Map.of(
                "id", 70L, "head_sha", "generated-commit", "path", ".github/workflows/ci.yml",
                "status", "completed", "conclusion", "failure")));
        when(gitHubService.listWorkflowJobs(any(), eq("owner"), eq("repo"), eq(70L))).thenReturn(List.of(
                Map.of("id", 702L, "name", "test", "status", "in_progress", "conclusion", "failure")
        ));
        when(persistenceService.findLatest(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(gitHubService.getWorkflowJobLogs(any(), eq("owner"), eq("repo"), eq(702L))).thenReturn("error: test failed");
        when(orchestratorService.analyzeLogs("error: test failed")).thenReturn(Map.of("status", "failed", "error_type", "TEST_FAILURE"));

        monitor.monitorGeneratedWorkflow(pipelineRun, new User(), "generated-commit", ".github/workflows/ci.yml");

        verify(persistenceService).save(eq("owner"), eq("repo"), eq(70L), eq(702L), any());
        assertEquals("completed", pipelineRun.getLogAnalysisStatus());
    }

    @Test
    void fallsBackToReturnedJobsWhenFailedWorkflowHasNoLiteralFailureConclusion() {
        PipelineRun pipelineRun = pipelineRun();
        WorkflowLogAnalysisMonitor monitor = monitor();
        Map<String, Object> job = new java.util.HashMap<>();
        job.put("id", 702L);
        job.put("name", "build");
        job.put("status", "completed");
        job.put("conclusion", "null");

        when(pipelineRunRepository.findById(7L)).thenReturn(Optional.of(pipelineRun));
        when(gitHubService.listWorkflowRuns(any(), eq("owner"), eq("repo"))).thenReturn(List.of(Map.of(
                "id", 70L, "head_sha", "generated-commit", "path", ".github/workflows/ci.yml",
                "status", "completed", "conclusion", "failure")));
        when(gitHubService.listWorkflowJobs(any(), eq("owner"), eq("repo"), eq(70L))).thenReturn(List.of(job));
        when(persistenceService.findLatest(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(gitHubService.getWorkflowJobLogs(any(), eq("owner"), eq("repo"), eq(702L))).thenReturn("npm ERR! Exit status 1");
        when(orchestratorService.analyzeLogs(anyString())).thenReturn(Map.of("status", "failed", "error_type", "BUILD_FAILURE"));

        monitor.monitorGeneratedWorkflow(pipelineRun, new User(), "generated-commit", ".github/workflows/ci.yml");

        verify(persistenceService).save(eq("owner"), eq("repo"), eq(70L), eq(702L), any());
        assertEquals("completed", pipelineRun.getLogAnalysisStatus());
    }

    @Test
    void retriesJobLookupWhenGitHubReturnsNoJobsImmediatelyAfterRunCompletion() {
        PipelineRun pipelineRun = pipelineRun();
        WorkflowLogAnalysisMonitor monitor = monitor();

        when(pipelineRunRepository.findById(7L)).thenReturn(Optional.of(pipelineRun));
        when(gitHubService.listWorkflowRuns(any(), eq("owner"), eq("repo"))).thenReturn(List.of(Map.of(
                "id", 70L, "head_sha", "generated-commit", "path", ".github/workflows/ci.yml",
                "status", "completed", "conclusion", "failure")));
        when(gitHubService.listWorkflowJobs(any(), eq("owner"), eq("repo"), eq(70L)))
                .thenReturn(List.of())
                .thenReturn(List.of(Map.of("id", 702L, "name", "test", "status", "completed", "conclusion", "failure")));
        when(persistenceService.findLatest(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(gitHubService.getWorkflowJobLogs(any(), eq("owner"), eq("repo"), eq(702L))).thenReturn("error: test failed");
        when(orchestratorService.analyzeLogs("error: test failed")).thenReturn(Map.of("status", "failed", "error_type", "TEST_FAILURE"));

        monitor.monitorGeneratedWorkflow(pipelineRun, new User(), "generated-commit", ".github/workflows/ci.yml");

        verify(gitHubService, times(2)).listWorkflowJobs(any(), eq("owner"), eq("repo"), eq(70L));
        verify(persistenceService).save(eq("owner"), eq("repo"), eq(70L), eq(702L), any());
        assertEquals("completed", pipelineRun.getLogAnalysisStatus());
    }

    private WorkflowLogAnalysisMonitor monitor() {
        return new WorkflowLogAnalysisMonitor(
                Runnable::run, gitHubService, persistenceService, pipelineRunRepository,
                orchestratorService, 1, 1);
    }

    private PipelineRun pipelineRun() {
        PipelineRun run = new PipelineRun();
        run.setId(7L);
        run.setOwner("owner");
        run.setRepoName("repo");
        return run;
    }
}

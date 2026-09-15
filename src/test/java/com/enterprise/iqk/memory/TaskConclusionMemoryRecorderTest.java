package com.enterprise.iqk.memory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskConclusionMemoryRecorderTest {

    @Test
    void savesTaskConclusionWithGoalAndTruncation() {
        MemoryService memoryService = mock(MemoryService.class);
        TaskConclusionMemoryRecorder recorder = new TaskConclusionMemoryRecorder(memoryService);
        String longConclusion = "c".repeat(2000);

        recorder.recordConclusion("tenant-1", "task-1", "RESEARCH",
                "研究新能源行业", longConclusion, "chat-1");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(memoryService).saveTaskMemory(anyString(), anyString(), content.capture(), anyString());
        assertThat(content.getValue())
                .startsWith("[RESEARCH] 目标: 研究新能源行业\n结论: ");
        assertThat(content.getValue().length()).isLessThanOrEqualTo("[RESEARCH] ".length() + "目标: ".length() + 161 + "\n结论: ".length() + 601);
    }

    @Test
    void omitsGoalSectionWhenUserInputMissing() {
        MemoryService memoryService = mock(MemoryService.class);
        TaskConclusionMemoryRecorder recorder = new TaskConclusionMemoryRecorder(memoryService);

        recorder.recordConclusion("tenant-1", "task-1", "REACT", null, "answer", null);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(memoryService).saveTaskMemory(anyString(), isNull(), content.capture(), anyString());
        assertThat(content.getValue()).isEqualTo("[REACT] 结论: answer");
    }

    @Test
    void skipsWhenConclusionMissing() {
        MemoryService memoryService = mock(MemoryService.class);
        TaskConclusionMemoryRecorder recorder = new TaskConclusionMemoryRecorder(memoryService);

        recorder.recordConclusion("tenant-1", "task-1", "RESEARCH", "topic", "   ", "chat-1");

        verify(memoryService, never()).saveTaskMemory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void memoryFailureNeverPropagatesToTaskCompletion() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.saveTaskMemory(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        TaskConclusionMemoryRecorder recorder = new TaskConclusionMemoryRecorder(memoryService);

        assertThatCode(() -> recorder.recordConclusion("tenant-1", "task-1", "RESEARCH", "topic", "answer", "chat-1"))
                .doesNotThrowAnyException();
    }
}

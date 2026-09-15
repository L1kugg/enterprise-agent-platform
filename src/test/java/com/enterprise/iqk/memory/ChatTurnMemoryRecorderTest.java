package com.enterprise.iqk.memory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTurnMemoryRecorderTest {

    @Test
    void savesTruncatedTurnAsConversationScopedShortMemory() {
        MemoryService memoryService = mock(MemoryService.class);
        ChatTurnMemoryRecorder recorder = new ChatTurnMemoryRecorder(memoryService);
        String longPrompt = "p".repeat(500);
        String longAnswer = "a".repeat(1000);

        recorder.recordTurn("tenant-1", "chat-1", longPrompt, longAnswer);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(memoryService).saveShortMemory(anyString(), anyString(), content.capture(), anyString());
        assertThat(content.getValue()).startsWith("Q: ").contains("\nA: ");
        // prompt capped at 200 chars, answer capped at 400, plus separators
        assertThat(content.getValue().length()).isLessThanOrEqualTo(4 + 201 + 3 + 401);
    }

    @Test
    void normalizesWhitespaceBeforePersisting() {
        MemoryService memoryService = mock(MemoryService.class);
        ChatTurnMemoryRecorder recorder = new ChatTurnMemoryRecorder(memoryService);

        recorder.recordTurn("tenant-1", "chat-1", "hello   world", "answer\nwith\nnewlines");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(memoryService).saveShortMemory(anyString(), anyString(), content.capture(), anyString());
        // newlines inside the answer would break the "Q:/A:" line format
        assertThat(content.getValue()).isEqualTo("Q: hello world\nA: answer with newlines");
    }

    @Test
    void skipsWhenPromptOrAnswerIsMissing() {
        MemoryService memoryService = mock(MemoryService.class);
        ChatTurnMemoryRecorder recorder = new ChatTurnMemoryRecorder(memoryService);

        recorder.recordTurn("tenant-1", "chat-1", "question", "   ");
        recorder.recordTurn("tenant-1", "chat-1", "", "answer");

        verify(memoryService, never()).saveShortMemory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void memoryFailureNeverPropagatesToTheChatStream() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.saveShortMemory(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        ChatTurnMemoryRecorder recorder = new ChatTurnMemoryRecorder(memoryService);

        assertThatCode(() -> recorder.recordTurn("tenant-1", "chat-1", "q", "a"))
                .doesNotThrowAnyException();
    }
}

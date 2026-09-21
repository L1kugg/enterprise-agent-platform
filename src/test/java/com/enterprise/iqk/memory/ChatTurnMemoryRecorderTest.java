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

        recorder.recordTurn("tenant-1", "user-1", "chat-1", longPrompt, longAnswer);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(memoryService).saveShortMemory(anyString(), anyString(), content.capture(), anyString());
        assertThat(content.getValue()).startsWith("Q: ").contains("\nA: ");
        // 提问截断到 200 字符、回答截断到 400 字符，外加分隔符
        assertThat(content.getValue().length()).isLessThanOrEqualTo(4 + 201 + 3 + 401);
    }

    @Test
    void normalizesWhitespaceBeforePersisting() {
        MemoryService memoryService = mock(MemoryService.class);
        ChatTurnMemoryRecorder recorder = new ChatTurnMemoryRecorder(memoryService);

        recorder.recordTurn("tenant-1", "user-1", "chat-1", "hello   world", "answer\nwith\nnewlines");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(memoryService).saveShortMemory(anyString(), anyString(), content.capture(), anyString());
        // 回答中的换行会破坏 "Q:/A:" 行格式，需要先规范化
        assertThat(content.getValue()).isEqualTo("Q: hello world\nA: answer with newlines");
    }

    @Test
    void skipsWhenPromptOrAnswerIsMissing() {
        MemoryService memoryService = mock(MemoryService.class);
        ChatTurnMemoryRecorder recorder = new ChatTurnMemoryRecorder(memoryService);

        recorder.recordTurn("tenant-1", "user-1", "chat-1", "question", "   ");
        recorder.recordTurn("tenant-1", "user-1", "chat-1", "", "answer");

        verify(memoryService, never()).saveShortMemory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void memoryFailureNeverPropagatesToTheChatStream() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.saveShortMemory(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        ChatTurnMemoryRecorder recorder = new ChatTurnMemoryRecorder(memoryService);

        assertThatCode(() -> recorder.recordTurn("tenant-1", "user-1", "chat-1", "q", "a"))
                .doesNotThrowAnyException();
    }
}

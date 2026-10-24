package com.enterprise.iqk.service;

import com.enterprise.iqk.config.properties.FeedbackProperties;
import com.enterprise.iqk.domain.AnswerFeedback;
import com.enterprise.iqk.domain.vo.AnswerFeedbackSubmitVO;
import com.enterprise.iqk.mapper.AnswerFeedbackMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnswerFeedbackInsightsTest {

    private AnswerFeedbackMapper mapper;
    private AnswerFeedbackService service;
    private FeedbackInsightsService insightsService;

    @BeforeEach
    void setUp() {
        mapper = mock(AnswerFeedbackMapper.class);
        FeedbackProperties properties = new FeedbackProperties();
        properties.setEnabled(false);
        service = new AnswerFeedbackService(mapper, properties, new ObjectMapper());
        insightsService = new FeedbackInsightsService(mapper);
    }

    private AnswerFeedbackSubmitVO payload(String reason) {
        AnswerFeedbackSubmitVO payload = new AnswerFeedbackSubmitVO();
        payload.setChatId("chat-1");
        payload.setRating(1);
        payload.setAnswer("答案");
        payload.setReason(reason);
        return payload;
    }

    @Test
    void dislikeReasonWhitelistIsEnforcedAndPersisted() {
        assertThatThrownBy(() -> service.submit("public", payload("胡说的")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason must be one of");

        service.submit("public", payload("不准确"));
        ArgumentCaptor<AnswerFeedback> captor = ArgumentCaptor.forClass(AnswerFeedback.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("不准确");
    }

    @Test
    void blankReasonIsStoredAsNull() {
        service.submit("public", payload(null));
        ArgumentCaptor<AnswerFeedback> captor = ArgumentCaptor.forClass(AnswerFeedback.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getReason()).isNull();
    }

    @Test
    void insightsComputePositiveRateWithZeroSafeEmptyState() {
        when(mapper.summarizeFeedback(eq("public"), any(LocalDateTime.class)))
                .thenReturn(Map.of("total", 8L, "positive", 6L, "negative", 2L, "neutral", 0L));
        when(mapper.feedbackTrendByDay(eq("public"), any(LocalDateTime.class)))
                .thenReturn(List.of(Map.of("day", LocalDate.now().toString(), "total", 8L, "positive", 6L, "negative", 2L)));
        when(mapper.topDislikeReasons(eq("public"), any(LocalDateTime.class), eq(5)))
                .thenReturn(List.of(Map.of("reason", "不准确", "count", 2L)));

        Map<String, Object> result = insightsService.insights("public", 7);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertThat(summary.get("total")).isEqualTo(8L);
        assertThat(summary.get("positiveRate")).isEqualTo(0.75);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) result.get("trend");
        // 缺天补零：7 天窗口返回 7 行
        assertThat(trend).hasSize(7);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) result.get("topDislikeReasons");
        assertThat(reasons.get(0)).containsEntry("reason", "不准确").containsEntry("count", 2L);
    }

    @Test
    void emptyFeedbackWindowYieldsExplicitZeroState() {
        when(mapper.summarizeFeedback(eq("public"), any(LocalDateTime.class))).thenReturn(null);
        when(mapper.feedbackTrendByDay(eq("public"), any(LocalDateTime.class))).thenReturn(List.of());
        when(mapper.topDislikeReasons(eq("public"), any(LocalDateTime.class), eq(5))).thenReturn(List.of());

        Map<String, Object> result = insightsService.insights("public", 7);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertThat(summary.get("total")).isEqualTo(0L);
        assertThat(summary.get("positiveRate")).isEqualTo(0.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) result.get("trend");
        assertThat(trend).hasSize(7);
        assertThat(trend.get(0)).containsEntry("total", 0L);
    }
}

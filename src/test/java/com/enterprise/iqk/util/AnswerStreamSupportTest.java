package com.enterprise.iqk.util;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 匀速播放工具：切片保序、拼接无损、空答案兜底单空片。 */
class AnswerStreamSupportTest {

    @Test
    void chunksLongAnswerIntoMultipleOrderedPieces() {
        String answer = "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸";
        List<String> chunks = AnswerStreamSupport.chunked(answer).collectList().block();
        assertEquals(List.of("一二三四五六", "七八九十甲乙", "丙丁戊己庚辛", "壬癸"), chunks);
        assertEquals(answer, String.join("", chunks));
    }

    @Test
    void shortAnswerStillEmitsSinglePiece() {
        List<String> chunks = AnswerStreamSupport.chunked("你好").collectList().block();
        assertEquals(List.of("你好"), chunks);
    }

    @Test
    void nullOrEmptyAnswerEmitsSingleEmptyPiece() {
        assertEquals(List.of(""), AnswerStreamSupport.chunked(null).collectList().block());
        assertEquals(List.of(""), AnswerStreamSupport.chunked("").collectList().block());
    }

    @Test
    void fluxIsDeferredSafeForMultipleSubscriptions() {
        Flux<String> flux = AnswerStreamSupport.chunked("重新订阅也能拿到完整内容");
        assertTrue(flux.collectList().block().size() > 1);
        assertTrue(flux.collectList().block().size() > 1);
    }
}

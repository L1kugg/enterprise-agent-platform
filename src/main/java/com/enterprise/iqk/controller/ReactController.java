package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.service.ReactAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * /ai/react 对话入口：同步 chat 与 SSE 流式 chatStream 两个端点，业务全权委托 ReactAgentService。
 * 成功请求把 chatId 落会话历史（流式端点在返回流之前先落库，宁可多记不漏记）。
 */
@RestController
@RequestMapping("/ai/react")
@RequiredArgsConstructor
public class ReactController {
    private final ReactAgentService reactAgentService;
    private final ChatHistoryRepository chatHistoryRepository;

    /** 同步对话：执行 ReAct 循环后保存会话历史并返回完整响应。 */
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReactChatResponseVO chat(@RequestBody ReactChatRequestVO request) {
        ReactChatResponseVO response = reactAgentService.chat(request);
        if (StringUtils.hasText(response.getChatId())) {
            chatHistoryRepository.save("react", response.getChatId());
        }
        return response;
    }

    /** SSE 流式对话：先保存会话历史，再返回 trace/token/done 事件流。 */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ReactChatRequestVO request) {
        if (request != null && StringUtils.hasText(request.getChatId())) {
            chatHistoryRepository.save("react", request.getChatId());
        }
        return reactAgentService.stream(request);
    }
}

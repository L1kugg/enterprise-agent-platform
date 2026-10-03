package com.enterprise.iqk.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * 流式安全的日志 Advisor：{@link org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor}
 * 的流式实现会经 ChatClientMessageAggregator 把整条增量流聚合成单个响应再吐出
 * （为了给日志一个"完整回答"），挂在 ChatClient 上之后所有流式端点的逐字输出
 * 都退化成"转完再一次性返回"。
 *
 * 这里保留同等的请求/响应 DEBUG 日志能力，但流式路径逐元素透传：
 * 完整内容通过 doOnComplete 旁路收集后再记录，不改变流的粒度与顺序。
 */
@Slf4j
public class PassThroughLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName() {
        return "PassThroughLoggerAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        logRequest(request);
        ChatClientResponse response = chain.nextCall(request);
        logResponse(textOf(response));
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        logRequest(request);
        StringBuilder content = new StringBuilder();
        return chain.nextStream(request)
                .doOnNext(response -> content.append(textOf(response)))
                .doOnComplete(() -> logResponse(content.toString()));
    }

    private void logRequest(ChatClientRequest request) {
        if (log.isDebugEnabled() && request != null && request.prompt() != null) {
            log.debug("LLM 请求: {}", request.prompt().getContents());
        }
    }

    private void logResponse(String content) {
        if (log.isDebugEnabled()) {
            log.debug("LLM 响应: {}", content);
        }
    }

    /** 单个流式分片的增量文本；空分片与空响应统一返回空串。 */
    private String textOf(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return "";
        }
        String text = response.chatResponse().getResult().getOutput().getText();
        return StringUtils.hasText(text) ? text : "";
    }
}

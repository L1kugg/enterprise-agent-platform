package com.enterprise.iqk.util;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 已整段生成答案的"匀速播放"工具：ReAct 规划器在 finish 决策里就已经
 * 把最终答案一次性生成好（reason 是阻塞式调用，要拿到完整文本才能解析出
 * action/answer），流式阶段只剩播放这条现成答案。直接 Flux.just 会整段
 * 一坨到达，前端观感就是"思考很久、突然蹦出全部答案"。
 *
 * 这里把答案切成小片按固定间隔发出，让这类答案与真流式路径观感一致。
 * 纯展示层匀速播放：不改变答案内容、不多花模型调用，只增加毫秒级播放时长。
 */
public final class AnswerStreamSupport {

    /** 每片字符数：太小会片数过多拉长播放，太大观感接近整段 */
    private static final int CHUNK_SIZE = 6;
    /** 片间隔毫秒数 */
    private static final long CHUNK_INTERVAL_MS = 15;

    private AnswerStreamSupport() {
    }

    /** 按固定间隔小片发出整段答案；空答案发出单个空片以保持下游收尾逻辑不变。 */
    public static Flux<String> chunked(String answer) {
        String text = answer == null ? "" : answer;
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            chunks.add(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE)));
        }
        if (chunks.isEmpty()) {
            chunks.add("");
        }
        return Flux.fromIterable(chunks).delayElements(Duration.ofMillis(CHUNK_INTERVAL_MS));
    }
}

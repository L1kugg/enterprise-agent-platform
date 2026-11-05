package com.enterprise.iqk.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型动作输入归一化：把模型"抄提示词"抄出来的两种常见变形在进策略守卫前修好——
 * ① 上游工具参数平铺在 action_input 顶层（工具目录只列参数名，模型不包 arguments），
 *    若不修会双重失败：arguments 缺失 + 平铺字段被守卫判 unknown；
 * ② arguments 写成 JSON 字符串而非对象（runtime 层会降级为空参数，上游报缺参）。
 * 只对必填 arguments 的动作（mcp_call / tool_call）生效，且只在"能修"时改写：
 * 平铺收编 = 把 schema 未声明的顶层字段移入 arguments；字符串解析 = 成功才替换。
 * 这是 R7.3"模型抄提示词的参数要做归一化防御"教训在 arguments 通道的对称落地。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionInputNormalizer {

    private final ActionSchemaRegistry registry;
    private final ObjectMapper objectMapper;

    public AgentAction normalize(AgentAction action) {
        if (action == null || action.actionInput() == null || action.actionInput().isEmpty()) {
            return action;
        }
        ActionSchema schema = registry.find(action.action()).orElse(null);
        if (schema == null || !schema.requiredFields().contains("arguments")) {
            return action;
        }
        Map<String, Object> input = new LinkedHashMap<>(action.actionInput());

        // ① arguments 是字符串：解析成功才替换为对象，失败保持原样让守卫/上游诚实报错
        if (input.get("arguments") instanceof String raw && StringUtils.hasText(raw)) {
            try {
                Object parsed = objectMapper.readValue(raw, Map.class);
                if (parsed instanceof Map<?, ?> parsedMap && !parsedMap.isEmpty()) {
                    Map<String, Object> arguments = new LinkedHashMap<>();
                    parsedMap.forEach((key, value) -> arguments.put(String.valueOf(key), value));
                    input.put("arguments", arguments);
                }
            } catch (Exception ex) {
                log.warn("action arguments 字符串解析失败（保持原样）: action={}, reason={}",
                        action.action(), ex.toString());
            }
        }

        // ② 平铺归一化：arguments 仍不是非空对象时，把 schema 未声明的顶层字段收编进 arguments
        boolean argumentsMissing = !(input.get("arguments") instanceof Map<?, ?> existing && !existing.isEmpty());
        if (argumentsMissing) {
            Map<String, Object> flattened = new LinkedHashMap<>();
            input.forEach((key, value) -> {
                if (!schema.knowsField(key)) {
                    flattened.put(key, value);
                }
            });
            if (!flattened.isEmpty()) {
                flattened.keySet().forEach(input::remove);
                input.put("arguments", flattened);
            }
        }

        if (input.equals(action.actionInput())) {
            return action;
        }
        return new AgentAction(action.action(), input, action.prompt(), action.tenantId(),
                action.chatId(), action.modelProfile(), action.taskId(), action.stepId(),
                action.agentId(), action.trustedRuntimeAccess(), action.knowledgeBaseIds());
    }
}

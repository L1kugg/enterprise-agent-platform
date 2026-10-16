package com.enterprise.iqk.service.platform;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** 平台工具测试入口：与聊天运行时共用 {@link ToolInvocationService} 的同一份执行逻辑（测试通过即可被调用）。 */
@Service
@RequiredArgsConstructor
public class PlatformToolTestService {

    private final PlatformAssetMapper assetMapper;
    private final ToolInvocationService toolInvocationService;

    public Map<String, Object> test(Long id, Map<String, Object> params) {
        PlatformAsset tool = ownedTool(id);
        JsonNode config = toolInvocationService.parseToolConfig(tool);
        long started = System.nanoTime();
        try {
            Map<String, Object> result = toolInvocationService.invoke(config, params);
            result.put("latencyMs", ToolInvocationService.elapsedMs(started));
            return result;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("status", "ERROR");
            failure.put("latencyMs", ToolInvocationService.elapsedMs(started));
            failure.put("error", safeMessage(ex));
            return failure;
        }
    }

    private PlatformAsset ownedTool(Long id) {
        PlatformAsset tool = assetMapper.findByIdAndType(id,
                com.enterprise.iqk.security.TenantContext.currentTenantId(), "tools");
        if (tool == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在");
        return tool;
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "工具测试失败" : message;
    }
}

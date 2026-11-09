package com.enterprise.iqk.memory;

import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆利用率服务（闭环的消费侧）：把既有 USE 留痕聚合为可运营数据——
 * 每条记忆的召回次数与最近召回时间回填、各层活跃/被召回/从未被召回汇总与 Top 榜。
 * 不新增采集，只消费 memory_event；聚合一次查询完成。
 */
@Service
@RequiredArgsConstructor
public class MemoryUtilizationService {

    private final MemoryItemMapper itemMapper;

    /** 把 USE 聚合统计回填进记忆条目（useCount/lastUsedAt 非表字段），供查询响应透出。 */
    public void attachUseStats(String tenantId, List<MemoryItemRecord> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = itemMapper.findUseStatsByTenant(TenantContext.normalize(tenantId));
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> byId = new HashMap<>();
        rows.forEach(row -> byId.put(String.valueOf(row.get("memoryId")), row));
        items.forEach(item -> {
            Map<String, Object> row = byId.get(item.getMemoryId());
            if (row != null) {
                Number count = (Number) row.get("useCount");
                Object lastUsed = row.get("lastUsedAt");
                item.setUseCount(count == null ? 0 : count.longValue());
                item.setLastUsedAt(lastUsed instanceof LocalDateTime t ? t : null);
            }
        });
    }

    /**
     * 利用率汇总：各层活跃/被召回/从未被召回计数 + Top 召回榜。
     * 数据全部来自既有的 memory_event 留痕——不新增采集，只消费。
     */
    public Map<String, Object> utilizationSummary(String tenantId) {
        String tenant = TenantContext.normalize(tenantId);
        List<Map<String, Object>> rows = itemMapper.findUseStatsByTenant(tenant);

        Map<String, Integer> activeByType = new LinkedHashMap<>();
        Map<String, Integer> usedByType = new LinkedHashMap<>();
        List<Map<String, Object>> top = new ArrayList<>();
        int[] neverUsed = {0};
        rows.forEach(row -> {
            String type = String.valueOf(row.getOrDefault("type", "?"));
            long useCount = row.get("useCount") == null ? 0
                    : ((Number) row.get("useCount")).longValue();
            activeByType.merge(type, 1, Integer::sum);
            if (useCount > 0) {
                usedByType.merge(type, 1, Integer::sum);
                if (top.size() < 10) {
                    top.add(new LinkedHashMap<>(row));
                }
            } else {
                neverUsed[0]++;
            }
        });
        top.sort((a, b) -> Long.compare(
                ((Number) b.getOrDefault("useCount", 0)).longValue(),
                ((Number) a.getOrDefault("useCount", 0)).longValue()));

        return Map.of(
                "activeByType", activeByType,
                "recalledAtLeastOnceByType", usedByType,
                "neverRecalled", neverUsed[0],
                "topRecalled", top);
    }
}

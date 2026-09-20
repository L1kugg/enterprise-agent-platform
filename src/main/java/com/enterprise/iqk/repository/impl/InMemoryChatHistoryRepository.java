package com.enterprise.iqk.repository.impl;

import com.enterprise.iqk.domain.vo.MessageVO;
import com.enterprise.iqk.domain.vo.PagedResult;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.security.TenantContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 注意：该实现目前没有活跃的注入点（MysqlChatHistoryRepository 标注了 @Primary）；
// 但它仍被注册为 bean，因此需对其共享状态做并发访问保护。
@Repository
public class InMemoryChatHistoryRepository implements ChatHistoryRepository {
    /** tenantId::type → 该类型下按保存顺序去重累积的 chatId 列表 */
    private final Map<String, List<String>> chatHistory = new ConcurrentHashMap<>();
    @Override
    public void save(String type, String chatId) {
        List<String> list = chatHistory.computeIfAbsent(scopedKey(type), k -> new ArrayList<>());
        synchronized (list) {
            if (list.contains(chatId)) {
                //说明这个chatId已经有了
                return;
            }
            list.add(chatId);
        }
    }

    @Override
    /** 分页返回该类型下已保存的 chatId；读取走同步快照避免与写入并发冲突。 */
    public PagedResult<String> getChatIds(String type, int page, int pageSize) {
        List<String> all = chatHistory.get(scopedKey(type));
        if (all == null) {
            all = new ArrayList<>();
        }
        List<String> snapshot;
        synchronized (all) {
            snapshot = new ArrayList<>(all);
        }
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(pageSize, 1);
        int fromIndex = Math.min((safePage - 1) * safePageSize, snapshot.size());
        int toIndex = Math.min(fromIndex + safePageSize, snapshot.size());
        return new PagedResult<>(snapshot.subList(fromIndex, toIndex), snapshot.size(), safePage, safePageSize);
    }

    @Override
    /** 消息明细不做内存存储，恒返回空分页结果。 */
    public PagedResult<MessageVO> getChatHistory(String type, String chatId, int page, int pageSize) {
        return new PagedResult<>(Collections.emptyList(), 0, Math.max(page, 1), Math.max(pageSize, 1));
    }

    /** 以 tenantId::type 作为租户内隔离的存储键。 */
    private String scopedKey(String type) {
        String tenantId = TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
        return tenantId + "::" + type;
    }
}

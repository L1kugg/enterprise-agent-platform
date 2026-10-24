package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.graph.GraphExtractionService;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.retrieval.KeywordIndexStore;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 文档批次清理：① 删除（deleteByChat，向量先行失败即中止）；
 * ② 知识库同名文档替换（后写胜）——新批次 SUCCEEDED 后退役同库同名旧批次，
 * 向量/关键词/图谱切片清空、fact 记忆软失效、任务行置 SUPERSEDED 保留审计。
 * 从 IngestionService 抽出（主类 500 行上限）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentReplaceService {

    private final IngestionJobMapper ingestionJobMapper;
    private final VectorStore vectorStore;
    private final KeywordIndexStore keywordIndexStore;
    private final GraphExtractionService graphExtractionService;
    private final MemoryService memoryService;

    /**
     * 替换入口：仅作用于 KB 归属且同名（规范化文件名）的文档；未归属文档与聊天上传不受影响。
     * 并发上传同名时后完成者退役先完成者；单批退役失败不中止其余批次（最坏情况旧版本暂留，
     * 下次上传同名再试），也绝不影响新文档已成功的入库结果。
     */
    /**
     * 删除一个入库批次（文档）：先按 tenant + chat 过滤清掉向量切片，再删磁盘原文件与任务记录。
     * 顺序不能反——向量删除失败直接抛异常中止，保证不出现"任务记录删了、切片还在库里当孤儿"。
     * 返回本次删除的文件名集合（去重）。tenantId 需已归一化。
     */
    public List<String> deleteByChat(String normalizedTenantId, String chatId) {
        List<IngestionJob> jobs = ingestionJobMapper.findLatestByChatId(normalizedTenantId, chatId, 100);
        if (jobs.isEmpty()) {
            return List.of();
        }
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression scope = builder
                .and(builder.eq("tenant_id", normalizedTenantId), builder.eq("chat_id", chatId))
                .build();
        vectorStore.delete(scope);
        int keywordRows = keywordIndexStore.deleteByChat(normalizedTenantId, chatId);
        // 图谱数据按同一 chatId 作用域联动清理；失败只留孤儿边不中止文档删除
        // （读路径 getNeighbors 对悬空边已容忍：实体查不到就跳过）。
        try {
            int kgRows = graphExtractionService.deleteByChat(normalizedTenantId, chatId);
            log.info("kg cleanup: tenant={}, chatId={}, rows={}", normalizedTenantId, chatId, kgRows);
        } catch (Exception kgEx) {
            log.warn("kg cleanup 失败（不影响文档删除）: tenant={}, chatId={}, reason={}",
                    normalizedTenantId, chatId, kgEx.toString());
        }
        // 沉淀自该文档的 fact 记忆联动软失效：证据源没了，"可信事实"继续召回只会放大幻觉。
        // best-effort——失败只留活事实不中止删除，且方法幂等（stale 条件更新），可重跑补齐。
        try {
            int factRows = memoryService.invalidateFactsForChat(normalizedTenantId, chatId);
            if (factRows > 0) {
                log.info("fact memory cleanup: tenant={}, chatId={}, rows={}",
                        normalizedTenantId, chatId, factRows);
            }
        } catch (Exception memEx) {
            log.warn("fact memory cleanup 失败（不影响文档删除）: tenant={}, chatId={}, reason={}",
                    normalizedTenantId, chatId, memEx.toString());
        }
        Set<String> removedFiles = new LinkedHashSet<>();
        for (IngestionJob job : jobs) {
            File source = new File(job.getFilePath());
            if (source.exists() && source.delete()) {
                removedFiles.add(job.getSourceName());
            }
        }
        ingestionJobMapper.deleteByChatIdAndTenant(normalizedTenantId, chatId);
        log.info("keyword index cleanup: tenant={}, chatId={}, rows={}",
                normalizedTenantId, chatId, keywordRows);
        log.info("ingestion document deleted: tenant={}, chatId={}, jobs={}, files={}",
                normalizedTenantId, chatId, jobs.size(), removedFiles);
        return new ArrayList<>(removedFiles);
    }

    public void retireSupersededBatches(IngestionJob job) {
        if (job.getKnowledgeBaseId() == null || !StringUtils.hasText(job.getSourceName())) {
            return;
        }
        String tenantId = TenantContext.normalize(job.getTenantId());
        List<IngestionJob> candidates = ingestionJobMapper.findReplaceCandidates(
                tenantId, job.getKnowledgeBaseId(), job.getSourceName(), job.getChatId());
        if (candidates.isEmpty()) {
            return;
        }
        for (IngestionJob oldJob : candidates) {
            try {
                retireBatch(tenantId, oldJob);
            } catch (Exception batchEx) {
                log.warn("旧批次退役失败: tenant={}, chatId={}, reason={}",
                        tenantId, oldJob.getChatId(), batchEx.toString());
            }
        }
        log.info("knowledge base doc replaced: tenant={}, kb={}, name={}, retiredBatches={}",
                tenantId, job.getKnowledgeBaseId(), job.getSourceName(), candidates.size());
    }

    /** 退役一个旧批次：向量先行（失败即中止该批），其余 best-effort，与 deleteDocumentByChat 同顺序语义。 */
    private void retireBatch(String tenantId, IngestionJob oldJob) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression scope = builder
                .and(builder.eq("tenant_id", tenantId), builder.eq("chat_id", oldJob.getChatId()))
                .build();
        vectorStore.delete(scope);
        int keywordRows = keywordIndexStore.deleteByChat(tenantId, oldJob.getChatId());
        try {
            graphExtractionService.deleteByChat(tenantId, oldJob.getChatId());
        } catch (Exception kgEx) {
            log.warn("退役批次 KG 清理失败（不中止）: tenant={}, chatId={}, reason={}",
                    tenantId, oldJob.getChatId(), kgEx.toString());
        }
        try {
            memoryService.invalidateFactsForChat(tenantId, oldJob.getChatId());
        } catch (Exception memEx) {
            log.warn("退役批次 fact 失效失败（不中止）: tenant={}, chatId={}, reason={}",
                    tenantId, oldJob.getChatId(), memEx.toString());
        }
        for (IngestionJob jobRow : ingestionJobMapper.findLatestByChatId(tenantId, oldJob.getChatId(), 100)) {
            File source = new File(jobRow.getFilePath());
            if (source.exists() && !source.delete()) {
                log.warn("退役批次原文件删除失败: {}", jobRow.getFilePath());
            }
        }
        int marked = ingestionJobMapper.markSupersededByChat(tenantId, oldJob.getChatId(), LocalDateTime.now());
        log.info("batch superseded: tenant={}, chatId={}, keywordRows={}, markedJobs={}",
                tenantId, oldJob.getChatId(), keywordRows, marked);
    }
}

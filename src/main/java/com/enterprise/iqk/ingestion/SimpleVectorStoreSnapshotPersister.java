package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.config.properties.VectorStoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * SimpleVectorStore 快照持久化：仅 simple 后端且配置了快照路径时生效。
 * 从 IngestionService 拆出的单一职责组件：在整个 worker 线程池范围内串行化快照写入，
 * 避免并发任务交错写入同一个快照文件；先写临时文件再原子替换，崩溃/并发读取方
 * 永远不会看到写了一半的文件。失败仅告警，不影响入库结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleVectorStoreSnapshotPersister {

    private final VectorStoreProperties vectorStoreProperties;

    /** 在整个 worker 线程池范围内串行化 SimpleVectorStore 快照写入。 */
    private final Object snapshotLock = new Object();

    /** 仅 simple 后端且配置了快照路径时持久化；其它后端/未配置路径直接跳过。 */
    public void persistIfNeeded(VectorStore vectorStore) {
        if (!(vectorStore instanceof SimpleVectorStore simpleVectorStore)) {
            return;
        }
        if (!"simple".equalsIgnoreCase(vectorStoreProperties.getBackend())) {
            return;
        }
        String storePath = vectorStoreProperties.getSimpleStorePath();
        if (!StringUtils.hasText(storePath)) {
            return;
        }
        try {
            Path path = Path.of(storePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            synchronized (snapshotLock) {
                // 先写入临时文件再原子替换快照，保证崩溃或并发读取方
                // 永远不会看到写了一半的文件。
                simpleVectorStore.save(tmp.toFile());
                try {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException atomicMoveNotSupported) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to persist SimpleVectorStore snapshot", e);
        }
    }
}

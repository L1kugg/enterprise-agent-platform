package com.enterprise.iqk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档分段（R8 分段治理）：入库切片的真实落库快照——分段查看/编辑/拆分/合并/重建向量的数据面。
 * 与向量库/关键词索引的对应关系：job_id + seg_index 即切片元数据里的 job_id + chunk_index。
 */
@TableName("document_segment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSegmentRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String chatId;
    private String jobId;
    /** 段序（与向量切片元数据 chunk_index 同源） */
    private Integer segIndex;
    private String content;
    /** 向量/关键词索引是否与 content 同步（编辑后未重嵌入为 0） */
    private Boolean vectorSynced;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

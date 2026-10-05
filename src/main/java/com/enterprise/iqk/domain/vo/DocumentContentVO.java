package com.enterprise.iqk.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文档内容预览视图：按磁盘原文件重解析出的切片文本块（按入库顺序排列）。
 * chunkCount 为本次实际返回的块数；truncated=true 表示正文超过单次上限、blocks 已截断。
 */
@Data
@Builder
public class DocumentContentVO {
    private String chatId;
    private String sourceName;
    private String sourceType;
    private int chunkCount;
    private boolean truncated;
    private List<Block> blocks;

    /** 单个内容块：page 为 PDF 页码（来自入库时 page_number 元数据，非 PDF 来源为 null）。 */
    @Data
    @Builder
    public static class Block {
        private int index;
        private Integer page;
        private String text;
    }
}

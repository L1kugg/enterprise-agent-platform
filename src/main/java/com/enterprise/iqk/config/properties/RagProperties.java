package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    private int retrieveTopK = 12;
    private int rerankTopK = 6;
    private double similarityThreshold = 0.45;
    /** 兜底捞回（放宽阈值重试）结果的绝对最低相似度：低于此值视为完全无关，整批作废；须低于 similarityThreshold */
    private double fallbackScoreFloor = 0.30;
    private double temperature = 0.2;
    private Split split = new Split();

    @Data
    public static class Split {
        private int chunkSize = 800;
        private int minChunkSize = 120;
        private int maxNumChunks = 100;
    }
}

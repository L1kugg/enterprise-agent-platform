package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.feedback")
public class FeedbackProperties {
    private boolean enabled = true;
    private String datasetPath = "evaluation/feedback_dataset.jsonl";
    // 限制数据集在磁盘上的占用，防止单一租户（或凭据泄露的租户）
    // 通过反复提交反馈耗尽磁盘空间。
    // 当文件达到或超过该大小时，写入器会轮转到带时间戳的
    // 同级文件，而不再继续追加。
    private long maxDatasetBytes = 50L * 1024L * 1024L; // 50 MiB
}

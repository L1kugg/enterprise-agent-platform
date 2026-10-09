package com.enterprise.iqk.domain.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("platform_asset")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformAsset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String assetType;
    private Long parentId;
    private String name;
    private String description;
    private String status;
    private String configJson;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

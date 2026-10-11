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
    /** 资产归属：TENANT=租户私有（默认）；SYSTEM=系统共享（仅 model-services/databases，落 public 主租户） */
    public static final String SCOPE_TENANT = "TENANT";
    public static final String SCOPE_SYSTEM = "SYSTEM";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String assetType;
    private String scope;
    private Long parentId;
    private String name;
    private String description;
    private String status;
    private String configJson;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

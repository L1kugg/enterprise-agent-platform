package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PlatformAssetMapper extends BaseMapper<PlatformAsset> {
    @Select("""
            SELECT COUNT(*) FROM platform_asset
            WHERE tenant_id = #{tenantId} AND asset_type = #{assetType}
              AND name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\'
            """)
    long countForList(@Param("tenantId") String tenantId,
                      @Param("assetType") String assetType,
                      @Param("keyword") String keyword);

    @Select("""
            SELECT * FROM platform_asset
            WHERE tenant_id = #{tenantId} AND asset_type = #{assetType}
              AND name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\'
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PlatformAsset> findForList(@Param("tenantId") String tenantId,
                                    @Param("assetType") String assetType,
                                    @Param("keyword") String keyword,
                                    @Param("limit") int limit,
                                    @Param("offset") long offset);

    @Select("""
            SELECT * FROM platform_asset
            WHERE id = #{id} AND tenant_id = #{tenantId} AND asset_type = #{assetType} LIMIT 1
            """)
    PlatformAsset findByIdAndType(@Param("id") Long id,
                                  @Param("tenantId") String tenantId,
                                  @Param("assetType") String assetType);
}

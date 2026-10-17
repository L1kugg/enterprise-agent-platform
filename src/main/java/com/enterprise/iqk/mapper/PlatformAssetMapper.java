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
              AND name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\'
            """)
    long countForList(@Param("tenantId") String tenantId,
                      @Param("assetType") String assetType,
                      @Param("keyword") String keyword);

    @Select("""
            SELECT * FROM platform_asset
            WHERE tenant_id = #{tenantId} AND asset_type = #{assetType}
              AND name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\'
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

    /**
     * 运行时可见性解析（系统共享资产，见 docs/platform-system-assets-design.md §3.2）：
     * 本租户私有 ∪ 系统共享（scope=SYSTEM，落 public 主租户）。绑定关系按 ID 存，
     * 两类来源 ID 唯一，无同名歧义。
     */
    @Select("""
            SELECT * FROM platform_asset
            WHERE id = #{id} AND asset_type = #{assetType}
              AND (tenant_id = #{tenantId} OR scope = 'SYSTEM') LIMIT 1
            """)
    PlatformAsset findByIdAndTypeVisible(@Param("id") Long id,
                                         @Param("tenantId") String tenantId,
                                         @Param("assetType") String assetType);

    /** 凭据型资产（model-services/databases）的列表查询：本租户 ∪ 系统共享。 */
    @Select("""
            SELECT * FROM platform_asset
            WHERE asset_type = #{assetType}
              AND (tenant_id = #{tenantId} OR scope = 'SYSTEM')
              AND name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\'
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PlatformAsset> findForListUnion(@Param("tenantId") String tenantId,
                                         @Param("assetType") String assetType,
                                         @Param("keyword") String keyword,
                                         @Param("limit") int limit,
                                         @Param("offset") long offset);

    /** 凭据型资产的计数查询：与 findForListUnion 同口径。 */
    @Select("""
            SELECT COUNT(*) FROM platform_asset
            WHERE asset_type = #{assetType}
              AND (tenant_id = #{tenantId} OR scope = 'SYSTEM')
              AND name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\'
            """)
    long countForListUnion(@Param("tenantId") String tenantId,
                           @Param("assetType") String assetType,
                           @Param("keyword") String keyword);

    /** 普通用户可见目录：仅发布态资产，最新更新在前，限量防整表翻页。 */
    @Select("""
            SELECT * FROM platform_asset
            WHERE tenant_id = #{tenantId} AND asset_type = #{assetType} AND status = 'PUBLISHED'
            ORDER BY updated_at DESC, id DESC
            LIMIT 100
            """)
    List<PlatformAsset> findPublishedByType(@Param("tenantId") String tenantId,
                                            @Param("assetType") String assetType);

    /** 普通用户知识库目录（FR-R5-01）：仅启用态知识库，最新更新在前。 */
    @Select("""
            SELECT * FROM platform_asset
            WHERE tenant_id = #{tenantId} AND asset_type = #{assetType} AND status = 'ACTIVE'
            ORDER BY updated_at DESC, id DESC
            LIMIT 100
            """)
    List<PlatformAsset> findActiveByType(@Param("tenantId") String tenantId,
                                         @Param("assetType") String assetType);
}

package com.enterprise.iqk.mapper;

import com.enterprise.iqk.domain.platform.PlatformRoleRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 平台角色管理 mapper：保留 roles/permissions 原表模型，仅补充管理查询与授权维护。 */
@Mapper
public interface PlatformRoleMapper {

    @Select("""
            SELECT id, role_name AS code, role_name AS name, description, builtin, created_at
            FROM roles
            ORDER BY id ASC
            """)
    List<PlatformRoleRecord> findAll();

    @Select("""
            SELECT id, role_name AS code, role_name AS name, description, builtin, created_at
            FROM roles WHERE id = #{id} LIMIT 1
            """)
    PlatformRoleRecord findById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM user_roles WHERE role_id = #{roleId}")
    long countUsersByRoleId(@Param("roleId") Long roleId);

    @Select("""
            SELECT p.permission_name
            FROM permissions p
            INNER JOIN role_permissions rp ON p.id = rp.permission_id
            WHERE rp.role_id = #{roleId}
            ORDER BY p.permission_name
            """)
    List<String> findPermissionsByRoleId(@Param("roleId") Long roleId);

    @Insert("""
            INSERT INTO roles (role_name, description, builtin, created_at)
            VALUES (#{code}, #{description}, 0, #{createdAt})
            """)
    int insert(@Param("code") String code,
               @Param("description") String description,
               @Param("createdAt") LocalDateTime createdAt);

    @Update("""
            UPDATE roles
            SET role_name = #{code}, description = #{description}
            WHERE id = #{id} AND builtin = 0
            """)
    int update(@Param("id") Long id,
               @Param("code") String code,
               @Param("description") String description);

    @Delete("DELETE FROM roles WHERE id = #{id} AND builtin = 0")
    int delete(@Param("id") Long id);

    @Delete("DELETE FROM role_permissions WHERE role_id = #{roleId}")
    int deletePermissions(@Param("roleId") Long roleId);

    @Insert("""
            INSERT INTO role_permissions (role_id, permission_id, created_at)
            SELECT #{roleId}, p.id, #{createdAt}
            FROM permissions p
            WHERE p.permission_name = #{permission}
            """)
    int insertPermissionIfPresent(@Param("roleId") Long roleId,
                                  @Param("permission") String permission,
                                  @Param("createdAt") LocalDateTime createdAt);
}

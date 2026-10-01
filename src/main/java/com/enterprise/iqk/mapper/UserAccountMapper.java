package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.UserAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    @Select("""
            SELECT * FROM users
            WHERE username = #{username}
            LIMIT 1
            """)
    UserAccount findByUsername(@Param("username") String username);

    @Select("""
            SELECT r.role_name
            FROM roles r
            INNER JOIN user_roles ur ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
            """)
    List<String> findRoleNamesByUserId(@Param("userId") Long userId);

    @Select("SELECT id FROM roles WHERE role_name = #{roleName} LIMIT 1")
    Long findRoleIdByName(@Param("roleName") String roleName);

    @Insert("INSERT INTO user_roles (user_id, role_id, created_at) VALUES (#{userId}, #{roleId}, #{createdAt})")
    int insertUserRole(@Param("userId") Long userId,
                       @Param("roleId") Long roleId,
                       @Param("createdAt") LocalDateTime createdAt);
}

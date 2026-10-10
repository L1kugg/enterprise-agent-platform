package com.enterprise.iqk.service.platform;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/** 数据库配置字段校验：仅允许 MySQL/PostgreSQL，字段长度和端口先收敛。 */
public final class PlatformDatabaseValidator {
    private PlatformDatabaseValidator() {
    }

    public static void validateTypeAndPort(String type, int port) {
        String normalized = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (!normalized.equals("MYSQL") && !normalized.equals("POSTGRESQL")
                && !normalized.equals("MARIADB") && !normalized.equals("SQLSERVER")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "当前仅支持 MySQL / PostgreSQL / MariaDB / SQL Server 连接测试");
        }
        if (port < 1 || port > 65535) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据库端口必须在 1-65535");
        }
    }
}

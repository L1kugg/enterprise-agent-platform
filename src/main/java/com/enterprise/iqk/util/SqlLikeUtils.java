package com.enterprise.iqk.util;

public final class SqlLikeUtils {
    private SqlLikeUtils() {
    }

    /**
     * 转义 MySQL LIKE 通配符，防止用户提供的关键词扩大搜索范围。
     * MySQL 默认的 LIKE 转义字符是反斜杠；在把值绑定到 {@code LIKE} 模式之前，
     * 应先经过本方法处理。
     *
     * <p>若不这么做，关键词 {@code %}（或 {@code _}、{@code \}）会匹配
     * 租户表中的所有行，使分页失效，并让任何搜索接口都变成
     * 一次请求即可触发的 DoS / 数据穷取通道。
     */
    public static String escapeForLike(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return keyword;
        }
        return keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}

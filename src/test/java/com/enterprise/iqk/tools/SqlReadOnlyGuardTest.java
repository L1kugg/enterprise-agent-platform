package com.enterprise.iqk.tools;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlReadOnlyGuard 逐规则单测：只读放行面与拒绝面的边界都在这里锁死，
 * 守卫是 query_database 的第一层防线，改规则必须同步对照本文件。
 */
class SqlReadOnlyGuardTest {

    private static final Set<String> DENIED = Set.of(
            "users", "roles", "user_roles", "refresh_tokens", "api_keys", "mysql");

    private final SqlReadOnlyGuard.Result result =
            SqlReadOnlyGuard.validate("SELECT id, name FROM course", 4000, 30, DENIED);

    private SqlReadOnlyGuard.Result validate(String sql) {
        return SqlReadOnlyGuard.validate(sql, 4000, 30, DENIED);
    }

    @Test
    void passesPlainSelectAndAppendsLimit() {
        SqlReadOnlyGuard.Result ok = validate("SELECT id, name FROM course");
        assertThat(ok.allowed()).isTrue();
        assertThat(ok.normalizedSql()).isEqualTo("SELECT id, name FROM course LIMIT 30");
    }

    @Test
    void normalizesWhitespaceAndMixedCase() {
        SqlReadOnlyGuard.Result ok = validate("  SeLeCt\n\t id  FROM\t\tcourse  ");
        assertThat(ok.allowed()).isTrue();
        assertThat(ok.normalizedSql()).isEqualTo("SeLeCt id FROM course LIMIT 30");
    }

    @Test
    void allowsWithCteAndLeadingParenSelect() {
        assertThat(validate("WITH t AS (SELECT 1 AS n) SELECT n FROM t").allowed()).isTrue();
        assertThat(validate("(SELECT 1)").allowed()).isTrue();
        // 前导圆括号不配对时拒绝
        assertThat(validate("(SELECT 1").reasonCode()).isEqualTo("not_select");
    }

    @Test
    void stripsCommentsBeforeScanning() {
        // 注释里的敏感词不算数，剥掉后正常放行
        assertThat(validate("SELECT 1 /* DROP TABLE users */").allowed()).isTrue();
        assertThat(validate("SELECT 1 -- insert into users\nFROM course").allowed()).isTrue();
        assertThat(validate("SELECT 1 # set something\nFROM course").allowed()).isTrue();
    }

    @Test
    void rejectsEmptyBlankAndOversizedSql() {
        assertThat(validate("").reasonCode()).isEqualTo("empty_sql");
        assertThat(validate("   \n\t ").reasonCode()).isEqualTo("empty_sql");
        SqlReadOnlyGuard.Result tooLong = SqlReadOnlyGuard.validate(
                "SELECT " + "a".repeat(4100), 4000, 30, DENIED);
        assertThat(tooLong.reasonCode()).isEqualTo("sql_too_long");
    }

    @Test
    void rejectsNonSelectStatements() {
        assertThat(validate("SHOW TABLES").reasonCode()).isEqualTo("not_select");
        assertThat(validate("EXPLAIN SELECT 1").reasonCode()).isEqualTo("not_select");
        assertThat(validate("UPDATE course SET name = 'x'").reasonCode()).isEqualTo("not_select");
        assertThat(validate("DESC course").reasonCode()).isEqualTo("not_select");
        // 直接写语句在开头检查就被拦
        assertThat(validate("GRANT ALL ON *.* TO 'u'@'%'").reasonCode()).isEqualTo("not_select");
    }

    @Test
    void rejectsMultiStatementButAllowsTrailingSemicolon() {
        SqlReadOnlyGuard.Result injected = validate("SELECT 1; DROP TABLE course");
        assertThat(injected.reasonCode()).isEqualTo("multi_statement");
        // 分号藏在字符串字面量里不算多语句
        assertThat(validate("SELECT 'a;b' FROM course").allowed()).isTrue();
        assertThat(validate("SELECT 1;").normalizedSql()).isEqualTo("SELECT 1 LIMIT 30");
    }

    @Test
    void rejectsWriteKeywordsEmbeddedInSelect() {
        // SELECT 开头的语句若夹带写操作（改数 CTE / FOR UPDATE / INTO 落盘等）在关键词层被拦
        assertThat(validate("WITH w AS (DELETE FROM course) SELECT * FROM w").reasonCode())
                .isEqualTo("forbidden_keyword:delete");
        assertThat(validate("WITH w AS (DROP TABLE course) SELECT 1").reasonCode())
                .isEqualTo("forbidden_keyword:drop");
        assertThat(validate("WITH w AS (TRUNCATE TABLE course) SELECT 1").reasonCode())
                .isEqualTo("forbidden_keyword:truncate");
        assertThat(validate("SELECT id FROM course WHERE id = 1 FOR UPDATE").reasonCode())
                .isEqualTo("forbidden_keyword:update");
        assertThat(validate("SELECT 1 INTO OUTFILE '/tmp/x'").reasonCode())
                .isEqualTo("forbidden_keyword:into");
        assertThat(validate("SELECT LOAD_FILE('/etc/passwd')").reasonCode())
                .isEqualTo("forbidden_keyword:load_file");
        assertThat(validate("SELECT * FROM course FOR SHARE").reasonCode())
                .isEqualTo("forbidden_clause:for_share");
    }

    @Test
    void rejectsReplaceIntoButAllowsReplaceFunction() {
        assertThat(validate("WITH w AS (REPLACE INTO school SELECT 1, 'x') SELECT 1").reasonCode())
                .isEqualTo("forbidden_keyword:replace");
        // 字符串函数 REPLACE(col,..) 的 replace 后面紧跟圆括号，前瞻放行
        assertThat(validate("SELECT REPLACE(name, 'a', 'b') FROM course").allowed()).isTrue();
    }

    @Test
    void allowsKeywordsInsideStringLiterals() {
        // 字面量先摘除再扫关键词：内容里出现黑名单词不误杀
        assertThat(validate("SELECT * FROM course WHERE remark = 'please do not drop this'").allowed())
                .isTrue();
        assertThat(validate("SELECT * FROM school WHERE name LIKE '%set%'").allowed()).isTrue();
    }

    @Test
    void rejectsUnbalancedQuote() {
        assertThat(validate("SELECT 'abc FROM course").reasonCode()).isEqualTo("unbalanced_quote");
    }

    @Test
    void rejectsDeniedTablesInAllReferenceForms() {
        assertThat(validate("SELECT * FROM users").reasonCode()).isEqualTo("denied_table:users");
        assertThat(validate("SELECT * FROM `api_keys`").reasonCode()).isEqualTo("denied_table:api_keys");
        assertThat(validate("SELECT * FROM app.users").reasonCode()).isEqualTo("denied_table:users");
        assertThat(validate("SELECT * FROM mysql.user").reasonCode()).isEqualTo("denied_table:mysql");
        assertThat(validate("SELECT c.* FROM course c JOIN roles r ON c.id = r.id").reasonCode())
                .isEqualTo("denied_table:roles");
        assertThat(validate("SELECT * FROM course UNION SELECT * FROM refresh_tokens").reasonCode())
                .isEqualTo("denied_table:refresh_tokens");
    }

    @Test
    void doesNotFlagDerivedTablesOrSimilarNames() {
        // 派生表后随圆括号不匹配表引用模式
        assertThat(validate("SELECT t.n FROM (SELECT 1 AS n) t").allowed()).isTrue();
        // 表名带前缀（user_logs 含 users 子串但不是整词命中）不误报
        assertThat(validate("SELECT * FROM user_logs").allowed()).isTrue();
    }

    @Test
    void allowsInformationSchemaForSchemaDiscovery() {
        assertThat(validate("SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = DATABASE()").allowed()).isTrue();
    }

    @Test
    void capsLimitInAllForms() {
        assertThat(validate("SELECT 1").normalizedSql()).isEqualTo("SELECT 1 LIMIT 30");
        assertThat(validate("SELECT * FROM course LIMIT 100").normalizedSql())
                .isEqualTo("SELECT * FROM course LIMIT 30");
        // 未超限的 LIMIT 原样保留（offset/大小写不动）
        assertThat(validate("SELECT * FROM course LIMIT 5").normalizedSql())
                .isEqualTo("SELECT * FROM course LIMIT 5");
        assertThat(validate("SELECT * FROM course limit 5 OFFSET 100").normalizedSql())
                .isEqualTo("SELECT * FROM course limit 5 OFFSET 100");
        // 逗号形态只收敛第二个数字（行数），offset 保留
        assertThat(validate("SELECT * FROM course LIMIT 10, 50").normalizedSql())
                .isEqualTo("SELECT * FROM course LIMIT 10, 30");
    }

    @Test
    void rejectionCarriesActionableMessageForModelRetry() {
        SqlReadOnlyGuard.Result denied = validate("SELECT password FROM users");
        assertThat(denied.reasonMessage()).contains("users", "受保护");
        assertThat(denied.normalizedSql()).isNull();
        // 校验通过时归一化 SQL 就是唯一执行文本
        assertThat(result.allowed()).isTrue();
    }
}

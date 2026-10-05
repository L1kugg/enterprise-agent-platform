package com.enterprise.iqk.tools;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 只读守卫（query_database 动作的纯函数校验层）：模型现场生成的 SQL 在触库前必须过这里。
 * 校验通过时返回归一化 SQL（剥注释、压空白、LIMIT 归一化），调用方只执行归一化后的文本。
 *
 * <p>防线定位：这是四层防御的最外层（守卫 → 只读会话 → 超时 → 行数/单元格截断），
 * 拦截写操作、多语句、敏感表；MySQL 服务器端的会话只读是最终兜底。
 * 已知取舍：不引入 SQL 词法分析器，字符串字面量先整体摘除再扫关键词
 * （避免 `LIKE '%set%'` 之类被误杀），字面量内部因此不参与校验——
 * 代价是字面量里写注释符/黑名单词不再触发拒绝，MySQL 把整串当一个语句执行，无安全影响。
 * 拒绝信息全部写进 reasonMessage（error 观测只保留 message，模型靠它改写重试）。
 */
public final class SqlReadOnlyGuard {

    /** 私有构造：纯静态工具类 */
    private SqlReadOnlyGuard() {
    }

    /** 块注释（含跨行） */
    private static final Pattern COMMENT_BLOCK = Pattern.compile("(?s)/\\*.*?\\*/");
    /** 双横线行注释 */
    private static final Pattern COMMENT_DASH = Pattern.compile("--[^\n]*");
    /** 井号行注释 */
    private static final Pattern COMMENT_HASH = Pattern.compile("#[^\n]*");
    /** MySQL 单引号字符串字面量（'' 转义视为内容） */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    /** FROM/JOIN 后的表引用：裸名或反引号包裹，可带 schema 前缀；派生表 "FROM (" 天然不匹配 */
    public static final Pattern TABLE_REF_PATTERN = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+`?([a-zA-Z0-9_$]+)`?(?:\\s*\\.\\s*`?([a-zA-Z0-9_$]+)`?)?");
    /** LIMIT 子句：支持 "LIMIT n"、"LIMIT o, c"、"LIMIT n OFFSET m" 三种形态 */
    private static final Pattern LIMIT_PATTERN = Pattern.compile(
            "(?i)\\blimit\\s+(\\d+)(\\s*,\\s*(\\d+))?(\\s+offset\\s+(\\d+))?");
    /**
     * 禁用整词黑名单（在剥掉注释与字面量的小写文本上扫描）：
     * replace 带否定前瞻放行字符串函数 REPLACE(col,..)，只拦 REPLACE [IGNORE] INTO 写语句；
     * load_file 含下划线自成整词，\bload\b 覆盖不到，必须并列显式列出；
     * into 整词封禁同时消灭 SELECT ... INTO OUTFILE/@var；
     * for update 已被 update 整词覆盖，锁短语只补 for share。
     */
    private static final Pattern FORBIDDEN_WORDS = Pattern.compile(
            "\\b(insert|update|delete|merge|truncate"
            + "|create|alter|drop|rename|explain"
            + "|grant|revoke|set|use|call|do|handler|flush|reset|shutdown|kill"
            + "|install|uninstall|purge|repair|optimize|analyze"
            + "|prepare|execute|deallocate|help"
            + "|begin|commit|rollback|savepoint|release|start|stop|xa"
            + "|load|load_file|infile|outfile|dumpfile|into"
            + "|lock|unlock"
            + "|replace(?!\\s*\\())\\b");
    /** 锁读短语：FOR SHARE（FOR UPDATE 已被 update 整词覆盖） */
    private static final Pattern FORBIDDEN_LOCK_PHRASE = Pattern.compile("(?i)\\bfor\\s+share\\b");

    /** 校验结果：allowed=false 时 normalizedSql 为 null，拒绝原因看 reasonCode/reasonMessage */
    public record Result(boolean allowed, String normalizedSql, String reasonCode, String reasonMessage) {
    }

    /**
     * 校验并归一化一条 SQL：只放行单条纯 SELECT（允许 WITH 开头的 CTE 与一对前导圆括号），
     * 追加/收敛 LIMIT 到 maxRows。任一规则命中即拒绝，拒绝信息面向模型可读、可直接照做。
     *
     * @param rawSql       模型生成的原始 SQL
     * @param maxSqlLength 归一化后允许的最大长度
     * @param maxRows      LIMIT 收敛目标（同时是后续取数的行数上限）
     * @param deniedTables 表/schema 黑名单（大小写不敏感）
     */
    public static Result validate(String rawSql, int maxSqlLength, int maxRows, Set<String> deniedTables) {
        if (!StringUtils.hasText(rawSql)) {
            return reject("empty_sql", "SQL 不能为空，请给出一条 SELECT 查询。");
        }
        // 先剥三类注释（块注释可跨行），再压空白：注释里藏的关键词/表名不参与后续校验
        String noComments = COMMENT_HASH.matcher(
                COMMENT_DASH.matcher(
                        COMMENT_BLOCK.matcher(rawSql).replaceAll(" ")).replaceAll(" ")).replaceAll(" ");
        String normalized = noComments.replaceAll("\\s+", " ").trim();
        if (normalized.length() > maxSqlLength) {
            return reject("sql_too_long", "SQL 超过 " + maxSqlLength + " 字符上限，请精简查询（减少列或子查询）。");
        }

        // 字面量摘除：引号不成对时直接拒绝（后续扫描与执行都可能被恶意构造误导）；
        // 占位符选无引号字符，避免干扰后续的引号平衡检查
        String scan = STRING_LITERAL.matcher(normalized).replaceAll("?");
        if (scan.contains("'")) {
            return reject("unbalanced_quote", "SQL 中的单引号不成对，请检查字符串字面量。");
        }

        // 单语句：剥掉一个尾分号后不允许再出现分号
        String noTrailingSemicolon = stripTrailingSemicolon(scan);
        if (noTrailingSemicolon.contains(";")) {
            return reject("multi_statement", "只允许一条 SELECT 语句，请去掉多余分号或拆分查询。");
        }

        // 必须以 SELECT / WITH / (SELECT 开头
        String lower = noTrailingSemicolon.toLowerCase(Locale.ROOT);
        boolean selectLike = lower.startsWith("select ") || lower.startsWith("select(")
                || lower.startsWith("with ") || lower.startsWith("(select");
        if (!selectLike) {
            return reject("not_select", "只允许 SELECT 查询（可用 WITH 开头的 CTE）；"
                    + "表结构可查 information_schema，例如 SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()。");
        }
        if (lower.startsWith("(") && countChar(lower, '(') != countChar(lower, ')')) {
            return reject("not_select", "圆括号不配对，请检查 SQL 括号。");
        }

        // 禁用整词与锁短语
        Matcher words = FORBIDDEN_WORDS.matcher(lower);
        if (words.find()) {
            return reject("forbidden_keyword:" + words.group(),
                    "SQL 含只读守卫禁止的关键词 \"" + words.group() + "\"，只允许纯查询（SELECT）。");
        }
        if (FORBIDDEN_LOCK_PHRASE.matcher(lower).find()) {
            return reject("forbidden_clause:for_share", "只读查询不允许 FOR SHARE 锁读，请去掉该子句。");
        }

        // 表黑名单（在剥字面量文本上提取，避免命中字面量里的表名）
        Set<String> denied = lowercaseSet(deniedTables);
        for (String table : extractTableNames(scan)) {
            if (denied.contains(table)) {
                return reject("denied_table:" + table,
                        "表 \"" + table + "\" 属于受保护对象，禁止查询（凭证/账号类数据）。");
            }
        }

        // LIMIT 归一化：缺失追加，超限收敛（在原文上改，保留模型书写的大小写；
        // 先剥尾分号，否则追加的 LIMIT 会落在分号之后形成非法语句）
        String executable = capLimit(stripTrailingSemicolon(normalized), maxRows);
        return new Result(true, executable, null, null);
    }

    /**
     * 从 SQL 文本提取 FROM/JOIN 后的表引用（小写、去重保序）；供租户过滤启发式复用。
     * schema 限定（如 app.users）时 schema 名与表名都返回——黑名单检查要两头都拦
     * （app.users 按表名拦、mysql.user 按schema 名拦），租户启发式对多余项天然不敏感。
     */
    public static List<String> extractTableNames(String sqlText) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = TABLE_REF_PATTERN.matcher(sqlText);
        while (matcher.find()) {
            tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
            if (matcher.group(2) != null) {
                tables.add(matcher.group(2).toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(tables);
    }

    /** 去掉末尾一个分号（允许尾随空白），中间的分号视为多语句。 */
    private static String stripTrailingSemicolon(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** LIMIT 收敛：无 LIMIT 追加；现有 LIMIT 的行数超过 maxRows 时改写为 maxRows（offset 保留）。 */
    private static String capLimit(String normalizedSql, int maxRows) {
        Matcher matcher = LIMIT_PATTERN.matcher(normalizedSql);
        if (!matcher.find()) {
            return normalizedSql + " LIMIT " + maxRows;
        }
        // 逗号形态 "LIMIT o, c" 的行数在第 3 组（第 2 组是带逗号的整段），其余形态行数是第 1 组
        int countGroup = matcher.group(2) != null ? 3 : 1;
        int count = Integer.parseInt(matcher.group(countGroup));
        if (count <= maxRows) {
            return normalizedSql;
        }
        return normalizedSql.substring(0, matcher.start(countGroup)) + maxRows
                + normalizedSql.substring(matcher.end(countGroup));
    }

    private static Result reject(String reasonCode, String reasonMessage) {
        return new Result(false, null, reasonCode, reasonMessage);
    }

    private static int countChar(String text, char target) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    private static Set<String> lowercaseSet(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            result.add(value.toLowerCase(Locale.ROOT));
        }
        return result;
    }
}

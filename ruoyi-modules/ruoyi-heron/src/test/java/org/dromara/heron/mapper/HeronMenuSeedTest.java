package org.dromara.heron.mapper;

import org.dromara.heron.support.HeronMysqlTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 菜单种子必须打在基座真实表形状上：自己造一张 sys_menu 只能证明脚本能跑，证明不了列数与列序对齐。
 */
@DisplayName("菜单与权限种子真实 MySQL 落库")
class HeronMenuSeedTest extends HeronMysqlTestSupport {

    private static final long SEED_MIN = 1761400000000002000L;
    private static final long SEED_MAX = 1761400000000002999L;
    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)create table %s \\(.*?\\) engine=innodb[^;]*;");
    private static final Pattern BASE_MENU_ID = Pattern.compile("insert into sys_menu values\\((\\d+)");
    private static final Pattern CODE_PERMISSION = Pattern.compile("\"(heron:[A-Za-z]+:[A-Za-z]+)\"");

    @Test
    @DisplayName("种子在基座表形状上幂等落库并覆盖后端全部静态权限")
    void seedAppliesIdempotentlyOnRealSysMenu() throws Exception {
        String baseSql = Files.readString(repoFile("script", "sql", "ry_vue.sql"));
        String seedSql = Files.readString(repoFile("script", "sql", "heron", "004-menu.sql"));
        long maxBaseMenuId = maxBaseMenuId(baseSql);
        assertTrue(maxBaseMenuId < SEED_MIN, "保留菜单段与基座菜单 ID 重叠: " + maxBaseMenuId);

        try (Connection connection = dataSource.getConnection()) {
            recreate(connection, baseSql, "sys_menu");
            recreate(connection, baseSql, "sys_role_menu");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(repoFile("script", "sql", "heron", "004-menu.sql")));
            int inserted = countSeedRows(connection);
            assertEquals(occurrences(seedSql, "insert into sys_menu values("), inserted,
                "菜单种子未整段写入，脚本行数与实际行数不一致");

            // 重复执行只重写保留段，行数不增；迁移重跑不能积累重复菜单。
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(repoFile("script", "sql", "heron", "004-menu.sql")));
            assertEquals(inserted, countSeedRows(connection), "菜单种子重复执行后不幂等");

            assertEmpty(connection, "SELECT perms FROM sys_menu WHERE menu_id BETWEEN " + SEED_MIN + " AND " + SEED_MAX
                + " AND perms <> '' GROUP BY perms HAVING COUNT(*) > 1", "权限标识重复");
            assertEmpty(connection, "SELECT menu_id FROM sys_menu m WHERE m.menu_id BETWEEN " + SEED_MIN + " AND " + SEED_MAX
                + " AND m.parent_id <> 0 AND NOT EXISTS (SELECT 1 FROM sys_menu p WHERE p.menu_id = m.parent_id)",
                "菜单父级缺失");
            assertEmpty(connection, "SELECT menu_id FROM sys_menu WHERE menu_id BETWEEN " + SEED_MIN + " AND " + SEED_MAX
                + " AND ((menu_type = 'C' AND (component IS NULL OR component = '' OR perms = '')))"
                + " OR (menu_type = 'F' AND perms = '')", "菜单/按钮缺少组件路径或权限");

            List<String> missing = new ArrayList<>(staticPermissions());
            missing.removeAll(permissions(connection));
            assertTrue(missing.isEmpty(), "后端权限缺少菜单种子: " + missing);
        }
    }

    /** 基座建表语句原样重建，只留本次校验需要的两张表，不复制一份可能漂移的表定义。 */
    private static void recreate(Connection connection, String baseSql, String table) throws SQLException {
        Matcher matcher = Pattern.compile(String.format(CREATE_TABLE.pattern(), table)).matcher(baseSql);
        assertTrue(matcher.find(), "基座 SQL 找不到 " + table + " 建表语句");
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + table);
            statement.execute(matcher.group());
        }
    }

    private static long maxBaseMenuId(String baseSql) {
        long max = 0;
        Matcher matcher = BASE_MENU_ID.matcher(baseSql);
        while (matcher.find()) max = Math.max(max, Long.parseLong(matcher.group(1)));
        return max;
    }

    private static int countSeedRows(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN " + SEED_MIN + " AND " + SEED_MAX)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static Set<String> permissions(Connection connection) throws SQLException {
        Set<String> perms = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT perms FROM sys_menu WHERE menu_id BETWEEN " + SEED_MIN + " AND " + SEED_MAX + " AND perms <> ''")) {
            while (result.next()) perms.add(result.getString(1));
        }
        return perms;
    }

    /** 只取代码里写死的完整权限串；拼动作名的动态入口由各自的授予清单覆盖。 */
    private static Set<String> staticPermissions() throws IOException {
        Path root = repoFile("ruoyi-modules", "ruoyi-heron", "src", "main", "java", "org", "dromara", "heron");
        Set<String> codes = new HashSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = CODE_PERMISSION.matcher(Files.readString(file));
                while (matcher.find()) codes.add(matcher.group(1));
            }
        }
        return codes;
    }

    private static void assertEmpty(Connection connection, String query, String message) throws SQLException {
        List<String> hits = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            while (result.next()) hits.add(result.getString(1));
        }
        assertTrue(hits.isEmpty(), message + ": " + hits);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) count++;
        return count;
    }

    /** surefire 的工作目录随执行入口变化，模块目录与仓库根都要能找到同一份脚本。 */
    private static Path repoFile(String... parts) {
        String relative = String.join("/", parts);
        for (String root : List.of("../..", ".")) {
            Path path = Path.of(root + "/" + relative);
            if (Files.exists(path)) return path;
        }
        throw new IllegalStateException("找不到文件: " + relative);
    }
}

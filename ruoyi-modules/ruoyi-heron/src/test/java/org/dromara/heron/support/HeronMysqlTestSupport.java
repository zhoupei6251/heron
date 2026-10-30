package org.dromara.heron.support;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.heron.domain.ProductionBizType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真实 MySQL 隔离库测试基类。缺环境或库名不合规直接失败，不跳过。
 */
@Tag("mysql")
@SpringJUnitConfig(HeronMysqlTestConfiguration.class)
public abstract class HeronMysqlTestSupport {

    private static final Pattern TEST_SCHEMA = Pattern.compile(
        "jdbc:mysql://[^/]+/(heron_it_[a-z0-9_]+)(?:\\?.*)?");

    public static final long ALL_SCOPE_USER = 3L;
    private static final List<String> ALL_SCOPE_PERMISSIONS = List.of(
        "heron:newsWork:all", "heron:newsWork:generate",
        "heron:proto:all", "heron:proto:generate");
    private static final java.util.concurrent.ConcurrentMap<Long, List<String>> PERMISSIONS =
        new java.util.concurrent.ConcurrentHashMap<>(Map.of(ALL_SCOPE_USER, ALL_SCOPE_PERMISSIONS));

    public static void grantPermissions(long userId, String... permissions) {
        PERMISSIONS.put(userId, List.of(permissions));
    }

    private static void resetPermissions() {
        PERMISSIONS.clear();
        PERMISSIONS.put(ALL_SCOPE_USER, ALL_SCOPE_PERMISSIONS);
    }

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeAll
    static void configureSaTokenJwt() {
        SaManager.getConfig().setJwtSecretKey("heron-test-jwt-secret-key-0123456789abcdef");
        StpUtil.setStpLogic(new StpLogicJwtForSimple());
        SaManager.setStpInterface(new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                try {
                    return PERMISSIONS.getOrDefault(Long.parseLong(String.valueOf(loginId)), List.of());
                } catch (NumberFormatException e) {
                    return List.of();
                }
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return List.of();
            }
        });
    }

    protected void resetSchema() {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS heron_usage_record");
                statement.execute("DROP TABLE IF EXISTS heron_task_step");
                statement.execute("DROP TABLE IF EXISTS heron_production_task");
                statement.execute("DROP TABLE IF EXISTS heron_production_lane");
                statement.execute("DROP TABLE IF EXISTS heron_test_version");
                statement.execute("DROP TABLE IF EXISTS heron_test_object");
            }
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(productionDdl()));
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE heron_test_object (
                      object_id bigint NOT NULL,
                      biz_type varchar(16) NOT NULL,
                      owner_id bigint NOT NULL,
                      revision bigint NOT NULL,
                      next_version bigint NOT NULL,
                      current_version_id bigint NULL,
                      active_task bigint NULL,
                      PRIMARY KEY (object_id),
                      UNIQUE KEY uk_test_object_active (active_task)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
                statement.execute("""
                    CREATE TABLE heron_test_version (
                      version_id bigint NOT NULL,
                      object_id bigint NOT NULL,
                      version_no bigint NOT NULL,
                      content json NOT NULL,
                      result_json json NULL,
                      task_id bigint NULL,
                      created_at datetime(3) NOT NULL,
                      PRIMARY KEY (version_id),
                      UNIQUE KEY uk_test_version_no (object_id, version_no)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("production-fixture.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException("初始化隔离测试库失败", e);
        }
    }

    protected void clearProductionData() {
        jdbc.update("DELETE FROM heron_usage_record");
        jdbc.update("DELETE FROM heron_task_step");
        jdbc.update("DELETE FROM heron_production_task");
        jdbc.update("DELETE FROM heron_test_version");
        jdbc.update("DELETE FROM heron_test_object");
    }

    protected void insertObject(long objectId, ProductionBizType type, long ownerId) {
        jdbc.update("""
                INSERT INTO heron_test_object
                  (object_id,biz_type,owner_id,revision,next_version)
                VALUES (?,?,?,1,1)
                """,
            objectId, type.name(), ownerId);
    }

    protected void loginAs(long userId) {
        logout();
        SaTokenContextMockUtil.setMockContext();
        StpUtil.login(userId, new SaLoginParameter().setExtra(LoginHelper.USER_KEY, userId));
    }

    @AfterEach
    void clearLoginContext() {
        resetPermissions();
        logout();
        try {
            SaTokenContextMockUtil.clearContext();
        } catch (RuntimeException ignored) {
            // 未建立模拟上下文时无需清理
        }
    }

    private void logout() {
        try {
            StpUtil.logout();
        } catch (RuntimeException ignored) {
            // 未登录或上下文不存在
        }
    }

    public static DataSource createDataSource() {
        String url = requireEnv("HERON_TEST_MYSQL_URL");
        String user = requireEnv("HERON_TEST_MYSQL_USER");
        String password = requireEnv("HERON_TEST_MYSQL_PASSWORD");
        Matcher matcher = TEST_SCHEMA.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalStateException("HERON_TEST_MYSQL_URL 必须指向 heron_it_ 前缀的隔离库");
        }
        String expectedSchema = matcher.group(1);
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DATABASE()")) {
            if (!result.next() || !expectedSchema.equals(result.getString(1))) {
                throw new IllegalStateException("HERON_TEST_MYSQL_URL 实际数据库与库名不一致");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("连接隔离测试库失败", e);
        }
        return dataSource;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少 " + name + "，真实 MySQL 测试必须显式提供隔离环境");
        }
        return value;
    }

    private static Path productionDdl() {
        for (String candidate : List.of(
            Path.of("..", "..", "script", "sql", "heron", "001-production.sql").toString(),
            Path.of("script", "sql", "heron", "001-production.sql").toString())) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IllegalStateException("找不到 001-production.sql");
    }
}

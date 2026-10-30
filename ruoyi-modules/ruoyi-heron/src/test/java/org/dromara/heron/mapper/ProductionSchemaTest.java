package org.dromara.heron.mapper;

import org.dromara.heron.support.HeronMysqlTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("生产底座真实 MySQL 模式")
class ProductionSchemaTest extends HeronMysqlTestSupport {

    @BeforeEach
    void setUp() {
        resetSchema();
    }

    @Test
    @DisplayName("四个唯一索引按契约列顺序建立")
    void uniqueKeysMatchContract() throws Exception {
        Map<String, String> uniqueKeys = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
                 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND NON_UNIQUE = 0
                 GROUP BY TABLE_NAME, INDEX_NAME
                 """)) {
            while (result.next()) {
                uniqueKeys.put(result.getString(1) + "." + result.getString(2), result.getString(3));
            }
        }
        assertEquals("actor_id,biz_type,biz_id,request_key", uniqueKeys.get("heron_production_task.uk_task_request"));
        assertEquals("biz_type,version_id", uniqueKeys.get("heron_production_task.uk_task_version"));
        assertEquals("task_id,attempt_no,stage", uniqueKeys.get("heron_task_step.uk_step"));
        assertEquals("task_id,attempt_no,call_id", uniqueKeys.get("heron_usage_record.uk_usage_call"));
    }

    @Test
    @DisplayName("缺少用量回执的调用保持未知")
    void usageWithoutProviderCountersStaysUnknown() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT input_tokens,output_tokens FROM heron_usage_record WHERE usage_id=101")) {
            assertTrue(result.next());
            assertNull(result.getObject(1));
            assertNull(result.getObject(2));
        }
    }

    @Test
    @DisplayName("进度与尝试次数取 DDL 默认值且未签发执行令牌")
    void taskStartsQueued() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 SELECT status,progress,attempt_no,run_token
                 FROM heron_production_task WHERE task_id=100
                 """)) {
            assertTrue(result.next());
            assertEquals("queued", result.getString(1));
            assertEquals(0, result.getInt(2));
            assertEquals(0, result.getInt(3));
            assertNull(result.getString(4));
        }
    }

    @Test
    @DisplayName("两条固定业务线已初始化")
    void twoLanesSeeded() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT biz_type FROM heron_production_lane ORDER BY biz_type")) {
            assertTrue(result.next());
            assertEquals("news_video", result.getString(1));
            assertTrue(result.next());
            assertEquals("prototype", result.getString(1));
            assertFalse(result.next());
        }
    }
}

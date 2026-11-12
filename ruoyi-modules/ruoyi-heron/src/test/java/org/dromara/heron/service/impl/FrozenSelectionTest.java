package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生产只能消费冻结快照。数据源在生产时被重新挑选过一次，
 * 结果就是「快照记的是 A、片子用的是 B」——这条测试锁住那个回归。
 */
@DisplayName("冻结选择：生产只读快照里的数据源")
class FrozenSelectionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode input(String json) {
        return JSON.readTree(json);
    }

    @Test
    @DisplayName("快照里有 selection 就原样使用，不回到路由")
    void usesFrozenSource() {
        DatasetRouter.Route route = NewsProductionPipeline.frozenSelection(input("""
            {"selection":{"dataSourceKey":"worldbank-gdp","digest":false}}
            """));
        assertNotNull(route, "有 selection 时必须返回冻结值");
        assertEquals("worldbank-gdp", route.sourceKey());
        assertFalse(route.digest());
    }

    @Test
    @DisplayName("digest 标志也从快照读，不重新推断")
    void keepsDigestFlag() {
        DatasetRouter.Route route = NewsProductionPipeline.frozenSelection(input("""
            {"selection":{"dataSourceKey":"eastmoney-index","digest":true}}
            """));
        assertNotNull(route);
        assertTrue(route.digest(), "速览标志必须沿用决策时的值");
    }

    @Test
    @DisplayName("没有 selection 的老快照返回 null，由调用方走兼容回退")
    void missingSelectionFallsBack() {
        assertNull(NewsProductionPipeline.frozenSelection(input("""
            {"kind":"data_board","title":"旧版本"}
            """)));
        // selection 在但数据源为空，同样算没有
        assertNull(NewsProductionPipeline.frozenSelection(input("""
            {"selection":{"dataSourceKey":""}}
            """)));
    }
}

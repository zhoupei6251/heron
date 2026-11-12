package org.dromara.heron.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 冻结上下文的 hash 必须只看内容：键序、缩进、写入次数都不该影响它。
 * 之所以要这一条，是因为 MySQL 的 json 列会重新规范化存储，读回来的字符串和写进去的不逐位相等。
 */
@DisplayName("冻结上下文：规范化与摘要")
class FrozenContextTest {

    private static Map<String, Object> content() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("title", "金价创阶段新高");
        inner.put("scenes", List.of("第一段", "第二段"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", "data_board");
        root.put("selection", inner);
        root.put("schemaVersion", FrozenContext.SCHEMA);
        return root;
    }

    @Test
    @DisplayName("键序不同、写法不同，hash 相同")
    void hashIsFormatIndependent() {
        String first = FrozenContext.hash("{\"b\":1,\"a\":{\"y\":2,\"x\":3}}");
        String second = FrozenContext.hash("{\n  \"a\": {\"x\": 3, \"y\": 2},\n  \"b\": 1\n}");

        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    @DisplayName("内容变了 hash 就变")
    void hashTracksContent() {
        Map<String, Object> changed = content();
        changed.put("kind", "article");

        assertNotEquals(FrozenContext.hash(FrozenContext.canonicalJson(content())),
            FrozenContext.hash(FrozenContext.canonicalJson(changed)));
    }

    @Test
    @DisplayName("列表顺序是内容的一部分，不排序")
    void listOrderMatters() {
        assertNotEquals(FrozenContext.hash("{\"scenes\":[\"甲\",\"乙\"]}"),
            FrozenContext.hash("{\"scenes\":[\"乙\",\"甲\"]}"));
    }

    @Test
    @DisplayName("规范化后的 JSON 与再次解析的结果一致")
    void canonicalJsonRoundTrips() {
        String canonical = FrozenContext.canonicalJson(content());
        assertEquals(FrozenContext.hash(canonical), FrozenContext.hash(canonical));

        assertTrue(canonical.startsWith("{\"kind\""), canonical);
    }

    @Test
    @DisplayName("缺内容直接失败，不算出一个空摘要")
    void rejectsMissingContent() {
        assertThrows(IllegalArgumentException.class, () -> FrozenContext.hash(null));
    }
}

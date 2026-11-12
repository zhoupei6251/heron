package org.dromara.heron.domain;

import cn.hutool.crypto.digest.DigestUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 冻结上下文的规范化与摘要：同一份逻辑内容必须算出同一个 hash，与键序、缩进、写入次数无关。
 *
 * <p>为什么不直接对写库字符串取 hash：MySQL 的 {@code json} 列会重新规范化存储
 * （去空白、按键重排），读回来的字符串和写进去的不逐位相等。所以 hash 只认「解析后再规范化」的结果，
 * 写入侧与校验侧走同一条路径，才不会出现「内容没变但 hash 变了」的假告警。
 */
public record FrozenContext(long contextId, long workId, long versionId, long taskId, int contextNo,
                            Long parentContextId, String revisionReason, String schemaVersion,
                            String contentHash, String contentJson) {

    /** 当前契约版本；读取方只在认出这个版本时才认为上下文可信。 */
    public static final String SCHEMA = "1.0";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static FrozenContext of(HeronProductionContext row) {
        return new FrozenContext(row.getContextId(), row.getWorkId(), row.getVersionId(), row.getTaskId(),
            row.getContextNo() == null ? 0 : row.getContextNo(), row.getParentContextId(),
            row.getRevisionReason(), row.getSchemaVersion(), row.getContentHash(), row.getContentJson());
    }

    /** 规范化 JSON 文本：键名升序、列表保持顺序、不带多余空白。 */
    public static String canonicalJson(Object content) {
        return JSON.writeValueAsString(canonical(content));
    }

    /** 内容摘要：解析 → 规范化 → sha256。写入侧与校验侧都走这里。 */
    public static String hash(String json) {
        if (json == null) {
            throw new IllegalArgumentException("冻结上下文缺失，不能计算摘要");
        }
        return DigestUtil.sha256Hex(canonicalJson(JSON.readTree(json)));
    }

    /**
     * 递归排序键名。写入侧的 Map 与读取侧的 JsonNode 都要经过它，
     * 这样两端算出来的字节序列才一致。
     */
    @SuppressWarnings("unchecked")
    public static Object canonical(Object value) {
        if (value instanceof JsonNode node) {
            return canonicalNode(node);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, item) -> ordered.put(String.valueOf(key), canonical(item)));
            return ordered;
        }
        if (value instanceof List<?> list) {
            List<Object> ordered = new ArrayList<>(list.size());
            for (Object item : list) {
                ordered.add(canonical(item));
            }
            return ordered;
        }
        return value;
    }

    private static Object canonicalNode(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> ordered = new TreeMap<>();
            node.properties().forEach(entry -> ordered.put(entry.getKey(), canonical(entry.getValue())));
            return ordered;
        }
        if (node.isArray()) {
            List<Object> ordered = new ArrayList<>();
            node.forEach(item -> ordered.add(canonical(item)));
            return ordered;
        }
        if (node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        return node.asString();
    }
}

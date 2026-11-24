package org.dromara.heron.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人工编辑策划结论时的沿用件：上一版冻结快照里的 selection / editorial 节点，逐字带进新版本。
 *
 * <p>生产链路只认冻结快照里的 selection 节点——数据源、取数口径、模板、策划结论都在里面。
 * 重建它要把取数口径（点数、节奏、指标、规划哈希）与机会字段（头线、信号）都还原回去，
 * 还原错一个字段就是按另一条口径取数，策划结论里的引用会解析不到。
 * 所以这里只换 story 一个键，其余逐字沿用。
 *
 * @param selectionNode 上一版的 selection 节点（换过 story）
 * @param editorialNode 上一版的 editorial 节点（补上谁改的痕迹）
 */
public record EditorialOverride(Map<String, Object> selectionNode, Map<String, Object> editorialNode) {

    public EditorialOverride {
        // 不能用 Map.copyOf：selection 节点里有 null 值（解说线没有 dataSourceKey），copyOf 遇 null 直接抛
        selectionNode = selectionNode == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(selectionNode));
        editorialNode = editorialNode == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(editorialNode));
    }
}

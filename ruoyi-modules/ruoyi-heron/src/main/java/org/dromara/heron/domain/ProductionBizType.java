package org.dromara.heron.domain;

import java.util.List;

/**
 * 固定生产业务类型，不接受任意业务标识。
 *
 * <p>视频四线共用同一条新闻流水线，靠冻结快照里的 {@code kind} 决定产物清单与闸门；
 * 枚举这一层只回答「这条任务属于哪条业务线」，用来分工 lane 容量锁、worker 轮询位与权限。
 */
public enum ProductionBizType {
    /** 文稿解说线：{@code kind=article}。 */
    news_explainer,
    /** 数据看板线：{@code kind=data_board}。 */
    data_board,
    /** 趋势洞察线：{@code kind=trend}。 */
    trend_insight,
    /** 知识解释线：{@code kind=knowledge_explainer}。 */
    knowledge_explainer,
    /**
     * 音乐视频线：当前只有枚举值与容量锁行，没有 domain / pipeline bean。
     *
     * <p>shortcut: 没有菜单种子行，{@code heron:musicVideo:*} 两个权限拿不到，
     * 接真实流水线时必须同时补 sys_menu 与角色授权，否则提交会卡在 404 上。
     */
    music_video;

    /** 共用新闻流水线的四条线。 */
    public static List<ProductionBizType> videoLanes() {
        return List.of(news_explainer, data_board, trend_insight, knowledge_explainer);
    }

    /**
     * 冻结快照 kind → 业务线。唯一映射点，别处不许再写 if。
     *
     * <p>取不到就按解说线：解说线是唯一不依赖数据源的线，猜错也比按数据线取空数据强。
     */
    public static ProductionBizType fromKind(String kind) {
        return switch (kind == null ? "" : kind) {
            case "data_board" -> data_board;
            case "trend" -> trend_insight;
            case "knowledge_explainer" -> knowledge_explainer;
            default -> news_explainer;
        };
    }

    /**
     * 业务线 → 冻结快照 kind。没有 selection 的手工路径靠它定 kind，
     * 免得重出一条数据看板作品时被默认成解说线。
     */
    public String kind() {
        return switch (this) {
            case data_board -> "data_board";
            case trend_insight -> "trend";
            case knowledge_explainer -> "knowledge_explainer";
            case news_explainer, music_video -> "article";
        };
    }

    /** 流水线 bean 名；还没接流水线的线返回 null，worker 见到 null 就不轮询。 */
    public String pipelineBean() {
        return this == music_video ? null : "newsProductionPipeline";
    }

    /** 业务域 bean 名；没有实现就返回 null，提交时按「能力缺失」拒绝。 */
    public String domainBean() {
        return this == music_video ? null : "newsProductionDomain";
    }

    public String generatePermission() {
        return this == music_video ? "heron:musicVideo:generate" : "heron:newsWork:generate";
    }

    public String allPermission() {
        return this == music_video ? "heron:musicVideo:all" : "heron:newsWork:all";
    }
}

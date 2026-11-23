package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import java.time.LocalDateTime;

/** 团队共享候选；候选是新闻事务的稳定锁锚点。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_news_candidate")
public class HeronNewsCandidate extends BaseEntity {

    /** 研究决策提升来的候选；为空表示热榜来源。 */
    public static final String SOURCE_RESEARCH = "research";
    /** 知识包条目生成的选题；sourceKey 指向知识源（data/knowledge/<sourceKey>）。 */
    public static final String SOURCE_KNOWLEDGE = "knowledge";
    /** 数据看板线的占位候选；不写就会被列语义（空=热榜）错归因成热榜。 */
    public static final String SOURCE_DATA_BOARD = "data_board";

    @TableId private Long candidateId;
    private String title;
    private String summary;
    /** 有界证据 JSON：items 与 evidenceTruncated。 */
    private String evidence;
    /**
     * 来源：research 表示由内容决策提升而来，knowledge 知识选题，data_board 数据看板占位，空表示热榜抓取。
     *
     * <p>为什么要独立成列而不是从证据 JSON 里现解析：自动模式要按来源做准入判定，
     * 靠证据结构推出来的结论会随证据形状变化而静默失效。
     */
    private String sourceKind;
    /** 研究候选的数据源 key；热榜来源为空。自动准入按它查源。 */
    private String sourceKey;
    /** 研究候选所属的研究 id；热榜来源为空。「某研究产出了哪些候选」按它查。 */
    private Long researchId;
    private String dedupKey;
    private String normalizedTitle;
    private Long hitCount;
    private Long heatScore;
    private Integer bestRank;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private Long claimedBy;
    private LocalDateTime claimedAt;
    private Long activeTaskId;
    /** 历史发布事实，只读；客户端不可设置。 */
    private LocalDateTime publishedAt;
}

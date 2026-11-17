package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 研究 attempt 实际取到的数据快照。
 *
 * <p>与机会发现时那份快照（{@code heron_data_opportunity.snapshot_json}）不是一回事：
 * 那份是「这条机会是怎么被发现的」，这份是「这次研究沿途真的看了什么」。
 * 结论的每一步都要能指到后者，否则「Agent 查过数据」只是一句声明。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_research_snapshot")
public class HeronResearchSnapshot extends BaseEntity {

    @TableId private Long snapshotId;
    private Long researchId;
    private Integer attemptNo;
    private String sourceKey;
    private String dataSha256;
    private String retrievedAt;
    private String contract;
    private Integer points;
    /** 快照原文：只存实际喂给模型的那份，不存全量历史。 */
    private String snapshotJson;
}

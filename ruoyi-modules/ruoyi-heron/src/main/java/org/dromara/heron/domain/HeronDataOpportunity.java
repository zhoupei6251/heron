package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据机会：一个公开数据源在某个窗口里的确定性信号。
 *
 * <p>只存算出来的事实与评分，不存模型表述——第一步不接入生产，也不调模型，
 * 目的是先拿真实数据校准阈值。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_data_opportunity")
public class HeronDataOpportunity extends BaseEntity {
    @TableId private Long opportunityId;
    private String sourceKey;
    private String entity;
    private String metric;
    private String shape;
    private String cadence;
    private Integer windowPoints;
    private Integer points;
    private String periodStart;
    private String periodEnd;
    private BigDecimal score;
    /** 信号 JSON：变化率、z-score、新极值、覆盖率。 */
    private String signals;
    /** 判断时看到的原始数据快照（内含上游 data_sha256）；源变化后靠它复核，不留就没法验。 */
    private String snapshotJson;
    private String headline;
    private String status;
    private String rejectReason;
    private Long usedVersionId;
    private String dedupKey;
    private LocalDateTime observedAt;
}

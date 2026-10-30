package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 单次外部调用账本，未知用量保持 null。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_usage_record")
public class HeronUsageRecord extends BaseEntity {

    @TableId(value = "usage_id")
    private Long usageId;

    private Long taskId;

    private Integer attemptNo;

    private String callId;

    private String stage;

    private String provider;

    private String model;

    private String providerCallId;

    private String status;

    private Long inputTokens;

    private Long outputTokens;

    private Long elapsedMs;

    private String errorCode;
}

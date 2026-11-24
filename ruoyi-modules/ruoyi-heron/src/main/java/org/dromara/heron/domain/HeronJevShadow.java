package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 一次 JEV SHADOW 对照：同一份候选概况，本地总编辑与外部判断器各选一次线。
 *
 * <p>只作阈值校准的数据来源，不进任何决策分支。失败、超时、预算耗尽记
 * {@code undetermined}，永远不等于「同意」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_jev_shadow")
public class HeronJevShadow extends BaseEntity {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_UNDETERMINED = "undetermined";

    @TableId private Long shadowId;
    private String poolId;
    private String poolHash;
    private String localLine;
    private String jevLine;
    private Double jevConfidence;
    private Boolean agree;
    private String status;
    private String model;
    private Long latencyMs;
    private String errorCode;
}

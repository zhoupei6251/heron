package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 生产容量锁行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_production_lane")
public class HeronProductionLane extends BaseEntity {

    @TableId(value = "biz_type")
    private String bizType;
}

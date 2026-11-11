package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/** 数据看板使用账本：完成才记账，失败或重跑不占用去重窗口。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_dataset_usage")
public class HeronDatasetUsage extends BaseEntity {
    @TableId private Long usageId;
    private String sourceKey;
    private String entity;
    private String metric;
    private Long versionId;
    private LocalDateTime usedAt;
}

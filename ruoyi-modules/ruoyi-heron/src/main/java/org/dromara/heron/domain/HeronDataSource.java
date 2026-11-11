package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/**
 * 数据看板数据源注册表；enabled '0' 启用、'1' 停用，与 heron_news_source 一致。
 * 探测状态与启用开关分开：能取到不等于进产线。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_data_source")
public class HeronDataSource extends BaseEntity {
    @TableId private String sourceKey;
    private String name;
    private String category;
    private String endpoint;
    private String historyEndpoint;
    private String httpMethod;
    private String dataFormat;
    private String cadence;
    private Integer historyDays;
    private String commercialOk;
    private String termsUrl;
    private String shapes;
    private String enabled;
    private String probeStatus;
    private String probeNote;
    private LocalDateTime lastUpdated;
    private Integer failureCount;
}

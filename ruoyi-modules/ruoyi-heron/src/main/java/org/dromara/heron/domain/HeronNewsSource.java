package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import java.time.LocalDateTime;

/** 新闻来源配置与采集租约；采集端点由 N2 提供。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_news_source")
public class HeronNewsSource extends BaseEntity {
    @TableId private Long sourceId;
    private String name;
    private String sourceType;
    private String url;
    private Integer intervalSeconds;
    /** 0 启用，1 停用。 */
    private String enabled;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime nextFetchAt;
    private String fetchToken;
    private LocalDateTime fetchLeaseUntil;
    private Integer failureCount;
    private String errorCode;
    private String errorReason;
}

package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronUsageRecord;

import java.io.Serial;
import java.io.Serializable;

/**
 * 对外用量视图，未知 token 保持 null。
 */
@Data
@AutoMapper(target = HeronUsageRecord.class)
public class ProductionUsageVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String provider;

    private String model;

    private String status;

    private Long inputTokens;

    private Long outputTokens;

    private Long elapsedMs;
}

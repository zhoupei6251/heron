package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 模型提出的策略假设。
 *
 * <p>每条都带可证伪条件与状态；它不是结论，也不是预测，只回答「拿什么去验证」。
 */
@Data
@TableName("heron_strategy_hypothesis")
public class HeronStrategyHypothesis implements Serializable {

    @TableId
    private Long hypothesisId;
    private String schemaVersion;
    private String claim;
    private String dimension;
    private String bucketValue;
    private String metric;
    private Integer minSamples;
    private Integer minWeeks;
    private String falsifyIf;
    private String basis;
    private String source;
    private Long generatedBy;
    private LocalDateTime generatedAt;
}

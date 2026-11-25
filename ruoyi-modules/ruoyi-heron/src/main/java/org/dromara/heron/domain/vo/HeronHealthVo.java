package org.dromara.heron.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 数据健康概览：回答「今天有没有正常产出」，不是报表。 */
@Data
public class HeronHealthVo implements Serializable {
    /** 统计生成时刻，前端据此判断数据是否陈旧。 */
    private LocalDateTime generatedAt;

    private int sourceTotal;
    private int sourceEnabled;
    private int sourceFailing;

    /** 最近一次成功采集的来源与时间；没有成功过则为空。 */
    private String lastSuccessSource;
    private LocalDateTime lastSuccessAt;

    /** 最近一次失败的来源与原因；没有失败则为空。 */
    private String lastErrorSource;
    private String lastErrorReason;

    private long todayCandidates;
    private long todayTasks;
    private long todayFailed;

    private long monthTasks;
    private long monthInputTokens;
    private long monthOutputTokens;

    /**
     * 自动产出当前是否被暂停，以及为什么。
     *
     * <p>「今天没产出」有两种完全不同的原因：链路坏了，或者刹车被人（或自动止损）拉了下来。
     * 只给数字不给这两个字段，看板只能看到一个没有解释的 0。
     */
    private boolean autoPaused;
    private String autoPauseReason;
}

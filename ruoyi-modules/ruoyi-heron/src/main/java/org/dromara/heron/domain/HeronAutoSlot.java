package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 自动产出槽位：日期+时刻唯一，重复触发不会重复产出。 */
@Data
@TableName("heron_auto_slot")
public class HeronAutoSlot implements Serializable {
    @TableId(value = "slot_date", type = IdType.INPUT)
    private LocalDate slotDate;
    /** 槽位标识：直接用配置里的 cron 表达式，改 cron 就等于换了槽位。 */
    private String slotTime;
    /** produced 已产出 / skipped 已跳过（含原因）/ failed 失败。 */
    private String status;
    private String detail;
    /** 总编辑选定的线：DATA_BOARD / NEWS_EXPLAINER / NO_PUBLISH。 */
    private String selectedLine;
    /** 总编辑给出的选题意图，进入生产输入而不是只当备注。 */
    private String editorialIntent;
    /** 为什么选这条线，或为什么今天不发。 */
    private String decisionReason;
    /** 这一槽认定的候选；线内编辑重新选定后以线内结果为准。 */
    private Long candidateId;
    /** 决策当时两条线的候选概况快照，用于回放「它看到了什么」。 */
    private String poolJson;
    private Long workId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

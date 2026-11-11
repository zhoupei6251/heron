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
    private Long workId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

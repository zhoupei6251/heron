package org.dromara.heron.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 人工策略覆盖请求。
 *
 * <p>覆盖只追加不改写原决策：解除要再写一条 unlock，删记录等于让策略被悄悄改过。
 * 锁必须带到期时间——没有期限的锁会永久改变生产策略，以后没人说得清是什么时候开始的。
 */
@Data
public class StrategyOverrideBo implements Serializable {

    /** 锁定的槽位日期。 */
    @NotNull
    private LocalDate slotDate;

    /** 锁定的槽位标识。 */
    @NotBlank
    private String slotTime;

    /** 动作：lock / veto / override / unlock。 */
    @NotBlank
    private String action;

    /** 作用域，形如 column:market。 */
    @NotBlank
    private String scope;

    /** 被作用的值；锁一个维度时可以留空。 */
    private String value;

    /** 为什么这么锁；必填，否则以后说不清。 */
    @NotBlank
    @Size(max = 500)
    private String reason;

    /** 锁的到期时间；lock 必填。 */
    private LocalDateTime scopeUntil;
}

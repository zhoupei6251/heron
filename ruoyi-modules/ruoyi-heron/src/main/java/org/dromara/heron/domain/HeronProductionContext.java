package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 生产冻结上下文：一次生产一份不可变输入。
 *
 * <p>只插入、不更新。改内容只能新建一行（新版本），旧行连同 hash 一起保持原样——
 * 这是「后续阶段读到的东西，就是当初冻结的东西」的唯一保证。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_production_context")
public class HeronProductionContext extends BaseEntity {
    @TableId private Long contextId;
    private Long workId;
    private Long versionId;
    private Long taskId;
    /** 同一作品的上下文序号，与版本号同源；续做与重试不新增序号。 */
    private Integer contextNo;
    /** 上一版上下文；首版为空。 */
    private Long parentContextId;
    /** 为什么产生新版本：reserve / resume / manual-retry。 */
    private String revisionReason;
    private String schemaVersion;
    private String contentHash;
    /** 规范化 JSON：键序稳定，hash 与内容一一对应。 */
    private String contentJson;
}

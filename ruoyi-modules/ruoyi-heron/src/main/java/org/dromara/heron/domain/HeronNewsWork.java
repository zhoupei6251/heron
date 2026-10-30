package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/** 候选唯一作品及可编辑草稿；生成状态只取生产任务。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_news_work")
public class HeronNewsWork extends BaseEntity {
    @TableId private Long workId;
    private Long candidateId;
    private Long ownerId;
    private String title;
    private String draft;
    /** 显式 CAS 保存，不原地覆盖既有版本。 */
    @Version private Long revision;
    private String templateKey;
    private String voiceKey;
    private Integer nextVersion;
    private Long currentVersionId;
    private Long activeTaskId;
}

package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/**
 * 生产冻结产物：一次任务一份文本类输入（当前用于文章正文）。
 *
 * <p>只插入不更新。首次执行写入，之后的重试与续做都读同一份——
 * 外部网页改版、正文被撤，都不该让「同一任务」读到不同的内容。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_production_artifact")
public class HeronProductionArtifact extends BaseEntity {
    @TableId private Long artifactId;
    private Long taskId;
    /** article / dataset / …；同一任务同一 kind 唯一。 */
    private String kind;
    private String sourceUrl;
    private String contentSha256;
    /** 规范化后的内容；当前只放文本类，二进制走对象存储，不进这张表。 */
    private String content;
    private Integer contentBytes;
    private LocalDateTime fetchedAt;
}

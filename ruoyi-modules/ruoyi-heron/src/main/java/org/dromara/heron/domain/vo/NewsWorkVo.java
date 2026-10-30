package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronNewsWork;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 作品草稿与当前版本、在途任务的安全组合视图。 */
@Data
@AutoMapper(target = HeronNewsWork.class)
public class NewsWorkVo implements Serializable {
    private Long workId;
    private Long candidateId;
    private Long ownerId;
    private String title;
    private String draft;
    private Long revision;
    private String templateKey;
    private String voiceKey;
    private Long currentVersionId;
    private Long activeTaskId;
    private NewsVideoVersionVo currentVersion;
    private ProductionTaskVo activeTask;
    private LocalDateTime updateTime;
}

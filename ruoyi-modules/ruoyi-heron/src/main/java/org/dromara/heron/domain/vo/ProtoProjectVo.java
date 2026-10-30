package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronProtoProject;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 项目当前草稿与生产指针；不暴露任何存储路径。 */
@Data
@AutoMapper(target = HeronProtoProject.class)
public class ProtoProjectVo implements Serializable {
    private Long projectId;
    private Long ownerId;
    private String name;
    private String requirement;
    private String canvas;
    private String themeKey;
    private Long revision;
    private Integer nextVersion;
    private Long currentVersionId;
    private Long activeTaskId;
    private String archived;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

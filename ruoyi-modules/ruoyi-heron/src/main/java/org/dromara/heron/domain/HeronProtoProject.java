package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/** 可编辑草稿；revision 用显式 CAS，归档不删除历史。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_proto_project")
public class HeronProtoProject extends BaseEntity {
    @TableId
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
}

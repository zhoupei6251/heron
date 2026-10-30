package org.dromara.heron.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronProtoProject;

import java.io.Serializable;

/** 草稿输入白名单；不接收 owner、版本号或文件路径。Unicode 长度在规范化后校验。 */
@Data
@AutoMapper(target = HeronProtoProject.class, reverseConvertGenerate = false)
public class ProtoProjectBo implements Serializable {
    private String name;
    private String requirement;
    private String canvas;
    private String themeKey;
    private Long expectedRevision;
}

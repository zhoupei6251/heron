package org.dromara.heron.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.heron.domain.MediaAsset;

import java.io.Serial;
import java.io.Serializable;

/** 素材可编辑元数据；尺寸、指纹与 OSS 指针只由服务端写入。 */
@Data
@AutoMapper(target = MediaAsset.class, reverseConvertGenerate = false)
public class MediaAssetBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    @NotBlank(message = "素材名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "素材名称不能超过 100 字", groups = {AddGroup.class, EditGroup.class})
    private String name;

    @NotBlank(message = "素材来源不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 2048, message = "素材来源不能超过 2048 字", groups = {AddGroup.class, EditGroup.class})
    private String sourceUri;

    @NotBlank(message = "素材许可不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 32, message = "素材许可代码不能超过 32 字", groups = {AddGroup.class, EditGroup.class})
    private String licenseCode;

    @NotBlank(message = "许可证明不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 2048, message = "许可证明不能超过 2048 字", groups = {AddGroup.class, EditGroup.class})
    private String licenseEvidenceUri;

    @Size(max = 1000, message = "署名不能超过 1000 字", groups = {AddGroup.class, EditGroup.class})
    private String attribution;

    @Size(max = 500, message = "场景标签不能超过 500 字", groups = {AddGroup.class, EditGroup.class})
    private String tags;
}

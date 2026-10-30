package org.dromara.heron.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.heron.domain.HeronNewsCandidate;
import java.io.Serializable;

/** 手工候选只接收文本，不接收来源 URL、认领或发布状态。 */
@Data
@AutoMapper(target = HeronNewsCandidate.class, reverseConvertGenerate = false)
public class NewsCandidateBo implements Serializable {
    @NotBlank @Size(max = 200) private String title;
    @NotBlank @Size(max = 16000) private String summary;
}

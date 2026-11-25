package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/** 一次场景返工的不可变审计；版本、上一版来源与音轨指纹在同一事务落库。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_scene_rework")
public class HeronSceneRework extends BaseEntity {
    @TableId(value = "scene_rework_id", type = IdType.INPUT)
    private Long sceneReworkId;
    private Long versionId;
    private Long previousVersionId;
    private String sceneId;
    private Integer sceneIndex;
    private Long startMs;
    private Long endMs;
    private String previousVideoSha256;
    private String sceneVideoSha256;
    private String videoSha256;
    private String contactSheetSha256;
    private String scriptSha256;
    private String previousScriptSha256;
    private String previousManifestSha256;
    private String audioSha256;
    private Integer renderCalls;
    private Integer ttsCalls;
    private Long durationMs;
    private String checksJson;
    private LocalDateTime createTime;
}

package org.dromara.heron.domain.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 视频模板一行。模板是文件不是表：key 来自 data/templates 下带 LOCK.json 的目录，
 * 元信息来自模板自己的 template.json，LOCK 不完整时仍列出但标为不可用。
 */
public record VideoTemplateVo(String key, String name, String description,
                              boolean locked, String lockProblem,
                              String templateVersion, String voiceKey, String assetManifestSha256,
                              List<String> supportedLines, List<String> supportedSceneTypes,
                              String aspectRatio, Integer minDuration, Integer maxDuration) implements Serializable {
}

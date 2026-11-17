package org.dromara.heron.domain.vo;

import java.io.Serializable;

/**
 * 视频视觉风格。色值由模板生成器从设计令牌里读出并写进 template.json，
 * 前端据此画风格预览——不在前端另抄一份色值，否则预览和成片会各说各话。
 *
 * @param key    风格标识，对应 data/templates/_shared/tokens/&lt;key&gt;.css
 * @param name   英文风格名，如 Professional News
 * @param label  中文标签，如 专业新闻
 * @param role   适用场景说明
 * @param bg     背景色
 * @param fg     前景色
 * @param accent 强调色
 */
public record VideoStyleVo(String key, String name, String label, String role,
                           String bg, String fg, String accent) implements Serializable {
}

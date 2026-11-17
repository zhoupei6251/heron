package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.VideoStyleVo;
import org.dromara.heron.domain.vo.VideoTemplateVo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 模板目录只读视图。模板不是数据库记录，是 data/templates 下带 LOCK.json 的目录；
 * 这里只做「有哪些、锁住的是什么」的翻译，不新增注册表——加模板仍然是加目录。
 */
@Service
@RequiredArgsConstructor
public class TemplateCatalogService {

    private final NewsProductionDomain domain;
    private final HeronAccessService access;

    public List<VideoTemplateVo> list() {
        access.requirePermission("heron:template:list");
        return domain.templateCatalog().stream().map(info -> {
            try {
                Map<String, Object> lock = domain.lockedAssets(info.key());
                return new VideoTemplateVo(info.key(), info.name(), info.description(), true, null,
                    text(lock.get("templateVersion")), text(lock.get("voiceKey")),
                    text(lock.get("assetManifestSha256")),
                    info.supportedLines(), info.supportedSceneTypes(), info.aspectRatio(),
                    info.minDuration(), info.maxDuration(), style(info.style()));
            } catch (HeronBusinessException e) {
                // LOCK 缺失或与目录不一致：模板仍要出现在列表里，否则「少了一个模板」没人发现
                return new VideoTemplateVo(info.key(), info.name(), info.description(), false, e.getMessage(),
                    null, null, null, info.supportedLines(), info.supportedSceneTypes(), info.aspectRatio(),
                    info.minDuration(), info.maxDuration(), style(info.style()));
            }
        }).toList();
    }

    private static VideoStyleVo style(NewsProductionDomain.TemplateStyle style) {
        // 老模板没有风格声明：返回 null 让前端画「未声明」，而不是编一套默认色
        return style == null ? null : new VideoStyleVo(style.key(), style.name(), style.label(), style.role(),
            style.bg(), style.fg(), style.accent());
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

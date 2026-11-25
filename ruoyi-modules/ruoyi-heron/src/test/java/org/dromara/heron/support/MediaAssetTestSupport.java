package org.dromara.heron.support;

import io.github.linpeilie.mapstruct.MapstructAutoConfiguration;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.heron.domain.MediaAssetToMediaAssetVoMapperImpl;
import org.dromara.heron.domain.bo.MediaAssetBoToMediaAssetMapperImpl;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** V5 单测最小 Spring 上下文：提供真实 JsonUtils 与 MapStruct 转换，不连数据库。 */
public final class MediaAssetTestSupport {

    private static AnnotationConfigApplicationContext context;

    private MediaAssetTestSupport() { }

    public static synchronized void initialize() {
        if (context != null && context.isActive()) {
            return;
        }
        context = new AnnotationConfigApplicationContext();
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.register(SpringUtils.class, MediaAssetBoToMediaAssetMapperImpl.class,
            MediaAssetToMediaAssetVoMapperImpl.class, MapstructAutoConfiguration.class);
        context.refresh();
    }
}

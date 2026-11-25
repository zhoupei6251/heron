package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.MediaAsset;
import org.dromara.heron.domain.MediaAssetLicensePolicy;
import org.dromara.heron.domain.bo.MediaAssetSelectionBo;
import org.dromara.heron.domain.vo.MediaAssetVo;

import java.util.List;

/** 素材持久层；选材查询先过滤生产许可与完整 OSS 元数据。 */
public interface MediaAssetMapper extends BaseMapperPlus<MediaAsset, MediaAssetVo> {

    default MediaAsset findForScene(MediaAssetSelectionBo request) {
        List<String> keywords = request.keywords() == null ? List.of() : request.keywords().stream()
            .filter(item -> item != null && !item.isBlank()).map(String::trim).toList();
        String orientation = request.orientation() == null ? "" : request.orientation().trim().toUpperCase(java.util.Locale.ROOT);
        var query = QueryBuilder.lambda(MediaAsset.class)
            .eq(MediaAsset::getAssetKind, MediaAsset.KIND_IMAGE)
            .in(MediaAsset::getLicenseCode, MediaAssetLicensePolicy.ALLOWED)
            .isNotNull(MediaAsset::getSourceUri)
            .isNotNull(MediaAsset::getLicenseEvidenceUri)
            .isNotNull(MediaAsset::getMediaType)
            .isNotNull(MediaAsset::getSizeBytes)
            .isNotNull(MediaAsset::getOssConfigKey)
            .isNotNull(MediaAsset::getOssKey)
            .isNotNull(MediaAsset::getOssUrl)
            .isNotNull(MediaAsset::getContentSha256)
            .ne(MediaAsset::getSourceUri, "")
            .ne(MediaAsset::getLicenseEvidenceUri, "")
            .ne(MediaAsset::getOssConfigKey, "")
            .ne(MediaAsset::getOssKey, "")
            .ne(MediaAsset::getOssUrl, "")
            .ne(MediaAsset::getContentSha256, "")
            .in(MediaAsset::getMediaType, "image/png", "image/jpeg")
            .gt(MediaAsset::getSizeBytes, 0)
            .gt(MediaAsset::getWidth, 0)
            .gt(MediaAsset::getHeight, 0)
            .eqIfText(MediaAsset::getOrientation, orientation);
        query.and(!keywords.isEmpty(), wrapper -> {
            for (String keyword : keywords) {
                wrapper.like(MediaAsset::getTags, keyword);
            }
        });
        return selectOne(query.orderByDesc(MediaAsset::getCreateTime).orderByDesc(MediaAsset::getId)
            .last("LIMIT 1").build());
    }
}

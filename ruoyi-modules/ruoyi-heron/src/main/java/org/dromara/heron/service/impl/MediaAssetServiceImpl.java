package org.dromara.heron.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.MediaAsset;
import org.dromara.heron.domain.MediaAssetLicensePolicy;
import org.dromara.heron.domain.bo.MediaAssetBo;
import org.dromara.heron.domain.bo.MediaAssetQueryBo;
import org.dromara.heron.domain.bo.MediaAssetSelectionBo;
import org.dromara.heron.domain.vo.MediaAssetGateVo;
import org.dromara.heron.domain.vo.MediaAssetSelectionVo;
import org.dromara.heron.domain.vo.MediaAssetVo;
import org.dromara.heron.mapper.MediaAssetMapper;
import org.dromara.heron.service.IMediaAssetService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAssetServiceImpl implements IMediaAssetService {

    private static final int MAX_DELETE_BATCH = 100;
    private static final Pattern SCENE_ID = Pattern.compile(MediaAssetSelectionBo.SCENE_ID_PATTERN);

    private final MediaAssetMapper assets;
    private final MediaAssetStorage storage;

    @Override
    public PageResult<MediaAssetVo> queryPageList(MediaAssetQueryBo bo, PageQuery pageQuery) {
        Map<String, Object> params = bo.getParams();
        var wrapper = QueryBuilder.lambda(MediaAsset.class)
            .likeIfText(MediaAsset::getName, bo.getName())
            .eqIfText(MediaAsset::getLicenseCode, bo.getLicenseCode())
            .eqIfText(MediaAsset::getAssetKind, bo.getAssetKind())
            .betweenParams(MediaAsset::getCreateTime, params, "beginCreateTime", "endCreateTime")
            .orderByDesc(MediaAsset::getCreateTime).build();
        var page = assets.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public MediaAssetVo queryById(long assetId) {
        MediaAssetVo vo = assets.selectVoById(assetId);
        if (vo == null) {
            throw new HeronBusinessException(404, "media_asset_not_found", "素材不存在：" + assetId);
        }
        return vo;
    }

    @Override
    public MediaAssetVo insertByBo(MediaAssetBo bo, MultipartFile file) {
        validateEditable(bo);
        MediaAsset asset = MapstructUtils.convert(bo, MediaAsset.class);
        asset.setId(IdWorker.getId());
        MediaAssetStorage.Stored stored = storage.upload(asset.getId(), file);
        asset.setAssetKind(MediaAsset.KIND_IMAGE);
        asset.setWidth(stored.width());
        asset.setHeight(stored.height());
        asset.setOrientation(orientation(stored.width(), stored.height()));
        asset.setContentSha256(stored.sha256());
        asset.setSizeBytes(stored.size());
        asset.setMediaType(stored.mediaType());
        asset.setOssConfigKey(stored.configKey());
        asset.setOssKey(stored.key());
        asset.setOssUrl(stored.url());
        asset.setDelFlag(MediaAsset.ACTIVE);
        try {
            storage.register(asset, stored);
            if (assets.insert(asset) != 1) {
                throw new HeronBusinessException(503, "media_asset_insert_failed", "素材元数据写入失败");
            }
        } catch (RuntimeException e) {
            cleanup(stored);
            throw e;
        }
        return queryById(asset.getId());
    }

    @Override
    public MediaAssetVo updateByBo(MediaAssetBo bo) {
        if (bo == null || bo.getId() == null) {
            throw new HeronBusinessException(400, "media_asset_id_missing", "修改素材必须提供主键");
        }
        validateEditable(bo);
        required(bo.getId());
        MediaAsset update = MapstructUtils.convert(bo, MediaAsset.class);
        if (assets.updateById(update) != 1) {
            throw new HeronBusinessException(409, "media_asset_update_conflict", "素材已被删除或更新，请刷新后重试");
        }
        return queryById(bo.getId());
    }

    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new HeronBusinessException(400, "media_asset_ids_missing", "删除素材必须提供主键");
        }
        for (Long id : ids) {
            if (id == null) {
                throw new HeronBusinessException(400, "media_asset_ids_missing", "删除素材主键不能为空");
            }
        }
        List<Long> uniqueIds = ids.stream().distinct().toList();
        if (uniqueIds.size() > MAX_DELETE_BATCH) {
            throw new HeronBusinessException(400, "media_asset_batch_too_large", "单次最多删除 100 个素材");
        }
        List<MediaAsset> existing = assets.selectByIds(uniqueIds);
        Set<Long> activeIds = existing.stream()
            .filter(asset -> !MediaAsset.DELETED.equals(asset.getDelFlag()))
            .map(MediaAsset::getId).collect(Collectors.toSet());
        for (Long id : uniqueIds) {
            if (!activeIds.contains(id)) {
                throw new HeronBusinessException(404, "media_asset_not_found", "素材不存在：" + id);
            }
        }
        return assets.deleteByIds(uniqueIds) > 0;
    }

    @Override
    public MediaAssetSelectionVo selectForScene(MediaAssetSelectionBo request) {
        if (request == null || request.sceneId() == null || request.sceneId().isBlank()
            || request.sceneNumber() == null || request.sceneNumber() < 1
            || request.sceneType() == null || request.sceneType().isBlank()) {
            throw new HeronBusinessException(400, "media_asset_scene_invalid", "场景选材缺少 ID、序号或类型");
        }
        if (!SCENE_ID.matcher(request.sceneId()).matches()) {
            throw new HeronBusinessException(400, "media_asset_scene_invalid",
                "场景 ID 仅允许 1-250 个 ASCII 字母、数字或 _ . : # [ ] -");
        }
        if (!"IMAGE".equalsIgnoreCase(request.sceneType().trim())) {
            return textOnly(request, "scene_type_not_image", "该场景不是图片场景，不占用素材");
        }
        if (request.assetId() != null && request.assetId() <= 0) {
            throw new HeronBusinessException(400, "media_asset_id_invalid", "显式素材 ID 非法，拒绝自动选材");
        }
        String requestedOrientation = request.orientation() == null ? "" : request.orientation().trim()
            .toUpperCase(Locale.ROOT);
        if (!requestedOrientation.isEmpty() && !Set.of("PORTRAIT", "LANDSCAPE", "SQUARE")
            .contains(requestedOrientation)) {
            throw new HeronBusinessException(400, "media_asset_orientation_invalid", "素材方向非法，拒绝自动选材");
        }
        MediaAsset candidate;
        boolean licenseChecked = false;
        if (request.assetId() != null) {
            candidate = assets.selectById(request.assetId());
        } else if (request.sourceUrl() != null && !request.sourceUrl().isBlank()) {
            candidate = assets.selectOne(QueryBuilder.lambda(MediaAsset.class)
                .eq(MediaAsset::getOssUrl, request.sourceUrl()).build());
            licenseChecked = true;
        } else {
            candidate = assets.findForScene(request);
            licenseChecked = true;
        }
        if (candidate == null || MediaAsset.DELETED.equals(candidate.getDelFlag())) {
            String code = request.assetId() == null ? "no_matching_asset" : "asset_missing";
            String reason = request.sourceUrl() != null && request.assetId() == null
                ? "稿件素材地址未登记到素材库，场景退回纯文字"
                : request.assetId() == null ? "没有匹配素材，场景退回纯文字" : "素材不存在或已删除，场景退回纯文字";
            return textOnly(request, code, reason, licenseChecked, null);
        }
        licenseChecked = true;
        requireAllowedLicense(candidate);
        if (!complete(candidate)) {
            return textOnly(request, "asset_incomplete", "素材生产依据、图像元数据或 OSS 指针不完整，场景退回纯文字",
                true, candidate.getId());
        }
        if (!suitable(candidate, request)) {
            return textOnly(request, "asset_not_suitable", "素材类型、方向或场景关键词不匹配，场景退回纯文字",
                true, candidate.getId());
        }
        MediaAssetSelectionVo result = base(request, "SELECTED", "selected", "已选择许可明确的素材");
        result.setImageValue(candidate.getOssUrl());
        result.setAssetId(candidate.getId());
        result.setAssetName(candidate.getName());
        result.setLicenseCode(candidate.getLicenseCode());
        result.setLicenseEvidenceUri(candidate.getLicenseEvidenceUri());
        result.setAttribution(candidate.getAttribution());
        result.setSourceUri(candidate.getSourceUri());
        result.setContentSha256(candidate.getContentSha256());
        result.setWidth(candidate.getWidth());
        result.setHeight(candidate.getHeight());
        result.setOssConfigKey(candidate.getOssConfigKey());
        result.setOssKey(candidate.getOssKey());
        result.setGate(gate(request, "selected", "已选择许可明确的素材", true, candidate.getId(), false));
        if (MediaAssetLicensePolicy.requiresAttribution(candidate.getLicenseCode())) {
            result.getGate().getMeasured().put("attributionRequired", true);
            result.getGate().getMeasured().put("attributionReady", true);
        }
        return result;
    }

    private MediaAsset required(long assetId) {
        MediaAsset asset = assets.selectById(assetId);
        if (asset == null || MediaAsset.DELETED.equals(asset.getDelFlag())) {
            throw new HeronBusinessException(404, "media_asset_not_found", "素材不存在：" + assetId);
        }
        return asset;
    }

    private void validateEditable(MediaAssetBo bo) {
        if (bo == null) {
            throw new HeronBusinessException(400, "media_asset_metadata_missing", "素材元数据不能为空");
        }
        bo.setName(trimToNull(bo.getName()));
        bo.setSourceUri(trimToNull(bo.getSourceUri()));
        bo.setLicenseEvidenceUri(trimToNull(bo.getLicenseEvidenceUri()));
        bo.setAttribution(trimToNull(bo.getAttribution()));
        if (bo.getName() == null || bo.getSourceUri() == null || bo.getLicenseEvidenceUri() == null) {
            throw new HeronBusinessException(400, "media_asset_metadata_incomplete", "素材名称、来源与许可证明不能为空");
        }
        requireHttpUri(bo.getSourceUri(), "素材来源");
        requireHttpUri(bo.getLicenseEvidenceUri(), "许可证明");
        String license = MediaAssetLicensePolicy.normalize(bo.getLicenseCode());
        if (!MediaAssetLicensePolicy.isAllowed(license)) {
            throw new HeronBusinessException(400, "media_asset_license_unknown", "素材许可未知或不在生产白名单，拒绝入库");
        }
        if (MediaAssetLicensePolicy.requiresAttribution(license) && bo.getAttribution() == null) {
            throw new HeronBusinessException(400, "media_asset_attribution_required", "CC_BY/CC_BY_SA 素材必须提供署名");
        }
        bo.setLicenseCode(license);
        bo.setTags(normalizeTags(bo.getTags()));
    }

    private static void requireAllowedLicense(MediaAsset asset) {
        if (!MediaAssetLicensePolicy.isAllowed(asset.getLicenseCode())) {
            throw new HeronBusinessException(409, "media_asset_license_rejected",
                "素材许可未知或不在生产白名单，拒绝进入生产：" + asset.getId());
        }
    }

    private static boolean complete(MediaAsset asset) {
        return MediaAsset.KIND_IMAGE.equals(asset.getAssetKind())
            && httpUri(asset.getSourceUri())
            && httpUri(asset.getLicenseEvidenceUri())
            && ("image/png".equals(asset.getMediaType()) || "image/jpeg".equals(asset.getMediaType()))
            && asset.getSizeBytes() != null && asset.getSizeBytes() > 0
                && asset.getSizeBytes() <= MediaAssetStorage.MAX_BYTES
            && asset.getContentSha256() != null && asset.getContentSha256().matches("[0-9a-fA-F]{64}")
            && StringUtils.isNotBlank(asset.getOssConfigKey())
            && StringUtils.isNotBlank(asset.getOssKey())
            && StringUtils.isNotBlank(asset.getOssUrl())
            && asset.getWidth() != null && asset.getWidth() > 0
            && asset.getHeight() != null && asset.getHeight() > 0
            && (!MediaAssetLicensePolicy.requiresAttribution(asset.getLicenseCode())
                || StringUtils.isNotBlank(asset.getAttribution()));
    }

    private static boolean suitable(MediaAsset asset, MediaAssetSelectionBo request) {
        String orientation = request.orientation() == null ? "" : request.orientation().trim().toUpperCase(Locale.ROOT);
        boolean directionMatches = switch (orientation) {
            case "" -> true;
            case "PORTRAIT" -> asset.getHeight() > asset.getWidth();
            case "LANDSCAPE" -> asset.getWidth() > asset.getHeight();
            case "SQUARE" -> asset.getWidth().equals(asset.getHeight());
            default -> throw new HeronBusinessException(400, "media_asset_orientation_invalid", "素材方向非法，拒绝自动选材");
        };
        String tags = StringUtils.defaultString(asset.getTags()).toLowerCase(Locale.ROOT);
        boolean keywordsMatch = request.keywords() == null || request.keywords().stream()
            .filter(item -> item != null && !item.isBlank())
            .allMatch(item -> tags.contains(item.trim().toLowerCase(Locale.ROOT)));
        return directionMatches && keywordsMatch;
    }

    private static MediaAssetSelectionVo textOnly(MediaAssetSelectionBo request, String code, String reason) {
        return textOnly(request, code, reason, false, null);
    }

    private static MediaAssetSelectionVo textOnly(MediaAssetSelectionBo request, String code, String reason,
                                                   boolean licenseChecked, Long candidateId) {
        MediaAssetSelectionVo result = base(request, "TEXT_ONLY", code, reason);
        result.setGate(gate(request, code, reason, licenseChecked, candidateId, true));
        return result;
    }

    private static MediaAssetSelectionVo base(MediaAssetSelectionBo request, String status, String code, String reason) {
        MediaAssetSelectionVo result = new MediaAssetSelectionVo();
        result.setSceneId(request.sceneId());
        result.setSceneNumber(request.sceneNumber());
        result.setSceneType(request.sceneType());
        result.setStatus(status);
        result.setReasonCode(code);
        result.setReason(reason);
        result.setImageVariable("scene" + request.sceneNumber() + "_image");
        result.setImageValue("");
        return result;
    }

    private static MediaAssetGateVo gate(MediaAssetSelectionBo request, String reasonCode, String reason,
                                          boolean licenseChecked, Long assetId, boolean fallbackToText) {
        return new MediaAssetGateVo("scene:" + request.sceneId(), reasonCode, reason,
            licenseChecked, assetId, fallbackToText);
    }

    private void cleanup(MediaAssetStorage.Stored stored) {
        try {
            storage.delete(stored);
        } catch (RuntimeException cleanupError) {
            log.warn("素材失败对象回收失败 key={} cause={}", stored.key(), cleanupError.getMessage());
        }
    }

    private static String orientation(int width, int height) {
        if (width == height) {
            return "SQUARE";
        }
        return width < height ? "PORTRAIT" : "LANDSCAPE";
    }

    private static String normalizeTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return null;
        }
        return StringUtils.str2List(tags, ",", true, true).stream().distinct().collect(Collectors.joining(","));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireHttpUri(String value, String label) {
        if (!httpUri(value)) {
            throw new HeronBusinessException(400, "media_asset_uri_invalid", label + "必须是完整的 http/https 地址");
        }
    }

    private static boolean httpUri(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

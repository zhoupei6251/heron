package org.dromara.heron.service.impl.shared.media;

import lombok.RequiredArgsConstructor;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.common.oss.model.Options;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronReferenceAsset;
import org.dromara.system.api.OssService;
import org.dromara.system.api.domain.OssDTO;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 参考素材对象存储边界：上传、sys_oss 登记与失败回收。 */
@Service
@RequiredArgsConstructor
public class ReferenceAssetStorage {

    private final HeronProductionProperties production;
    private final OssService ossService;

    /**
     * 上传下载完成的参考视频到 OSS；对象键按平台/awemeId/租约令牌/SHA-256 定位。
     *
     * @param asset 参考素材行，提供平台、awemeId 与当前 runToken
     * @param file  下载器落盘的临时 MP4
     * @return 对象键、URL、大小与摘要
     * @throws HeronBusinessException 503 表示上传失败
     */
    public Stored upload(HeronReferenceAsset asset, ReferenceVideoDownloader.Downloaded file) {
        String configKey = configKey();
        String key = "heron/reference/" + safe(asset.getPlatform()) + "/" + safe(asset.getAwemeId())
            + "/" + safe(asset.getRunToken()) + "/" + file.sha256() + ".mp4";
        try {
            OssClient oss = OssFactory.instance(configKey);
            var result = oss.upload(key, file.path(), Options.create().setContentType("video/mp4")
                .setMetadata(Map.of("sha256", file.sha256())));
            return new Stored(result.key(), result.url(), file.size(), file.sha256(), "video/mp4");
        } catch (RuntimeException e) {
            throw new HeronBusinessException(503, "reference_oss_upload_failed", "参考视频上传 OSS 失败: " + e.getMessage());
        }
    }

    /** 把参考视频登记到 sys_oss，扩展信息记录来源平台、awemeId 与摘要。 */
    public void register(HeronReferenceAsset asset, Stored stored) {
        String configKey = configKey();
        OssDTO row = new OssDTO();
        row.setService(configKey);
        row.setFileName(stored.key());
        row.setOriginalName(asset.getAwemeId() + ".mp4");
        row.setFileSuffix(".mp4");
        row.setUrl(stored.url());
        row.setExt1(JsonUtils.toJsonString(Map.of("bizType", "reference_asset", "fileSize", stored.size(),
            "contentType", stored.mediaType(), "source", "heron", "refId", Long.toString(asset.getId()),
            "refType", "reference_asset", "sha256", stored.sha256(), "platform", asset.getPlatform(),
            "awemeId", asset.getAwemeId(), "isTemp", false)));
        try {
            ossService.registerUploaded(row);
        } catch (RuntimeException e) {
            throw new HeronBusinessException(503, "reference_oss_register_failed", "参考视频 OSS 元数据登记失败: " + e.getMessage());
        }
    }

    /** 删除 OSS 对象并注销 sys_oss 登记；用于下载失败后的对象回收。 */
    public void delete(HeronReferenceAsset asset, Stored stored) {
        String configKey = configKey();
        OssFactory.instance(configKey).delete(stored.key());
        ossService.unregisterUploaded(configKey, stored.key());
    }

    private String configKey() {
        String key = production.getOssConfigKey();
        if (key == null || key.isBlank()) {
            throw new HeronBusinessException(503, "reference_oss_not_configured", "参考素材尚未配置 OSS");
        }
        return key;
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public record Stored(String key, String url, long size, String sha256, String mediaType) { }
}

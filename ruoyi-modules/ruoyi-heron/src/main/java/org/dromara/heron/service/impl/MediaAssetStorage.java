package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.common.oss.model.Options;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.MediaAsset;
import org.dromara.system.api.OssService;
import org.dromara.system.api.domain.OssDTO;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.awt.image.BufferedImage;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 素材对象存储边界：校验真实图像、计算指纹与尺寸、上传并登记 sys_oss。 */
@Service
@RequiredArgsConstructor
public class MediaAssetStorage {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PIXELS = 40_000_000L;
    private static final int MAX_DIMENSION = 20_000;

    private final HeronProductionProperties production;
    private final OssService ossService;

    public Stored upload(long assetId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new HeronBusinessException(400, "media_asset_file_missing", "素材文件不能为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new HeronBusinessException(413, "media_asset_too_large", "素材文件不能超过 10 MiB");
        }
        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new HeronBusinessException(400, "media_asset_unreadable", "素材文件无法读取", e);
        }
        String sha256 = DigestUtil.sha256Hex(bytes);
        ImageInfo image = imageInfo(bytes);
        String suffix = "PNG".equals(image.format()) ? ".png" : ".jpg";
        String key = "heron/media/" + assetId + "/" + sha256 + suffix;
        String configKey = configKey();
        try {
            OssClient client = OssFactory.instance(configKey);
            var result = client.upload(key, bytes, Options.create().setContentType(image.mediaType())
                .setMetadata(Map.of("sha256", sha256)));
            return new Stored(configKey, result.key(), result.url(), bytes.length, sha256, image.mediaType(),
                image.width(), image.height());
        } catch (RuntimeException e) {
            throw new HeronBusinessException(503, "media_asset_oss_upload_failed", "素材上传 OSS 失败: " + e.getMessage(), e);
        }
    }

    public void register(MediaAsset asset, Stored stored) {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("bizType", "media_asset");
        ext.put("fileSize", stored.size());
        ext.put("contentType", stored.mediaType());
        ext.put("source", "heron");
        ext.put("refId", Long.toString(asset.getId()));
        ext.put("refType", "media_asset");
        ext.put("sha256", stored.sha256());
        ext.put("width", stored.width());
        ext.put("height", stored.height());
        ext.put("licenseCode", asset.getLicenseCode());
        ext.put("sourceUri", asset.getSourceUri());
        ext.put("isTemp", false);
        OssDTO row = new OssDTO();
        row.setService(stored.configKey());
        row.setFileName(stored.key());
        row.setOriginalName(asset.getName() + ("image/png".equals(stored.mediaType()) ? ".png" : ".jpg"));
        row.setFileSuffix("image/png".equals(stored.mediaType()) ? ".png" : ".jpg");
        row.setUrl(stored.url());
        row.setExt1(JsonUtils.toJsonString(ext));
        try {
            ossService.registerUploaded(row);
        } catch (RuntimeException e) {
            throw new HeronBusinessException(503, "media_asset_oss_register_failed", "素材 OSS 元数据登记失败", e);
        }
    }

    public void delete(Stored stored) {
        OssFactory.instance(stored.configKey()).delete(stored.key());
        ossService.unregisterUploaded(stored.configKey(), stored.key());
    }

    private ImageInfo imageInfo(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new HeronBusinessException(400, "media_asset_image_invalid", "素材不是可读取的图像");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new HeronBusinessException(400, "media_asset_image_invalid", "素材不是可读取的图像");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toUpperCase(java.util.Locale.ROOT);
                if (!"PNG".equals(format) && !"JPEG".equals(format)) {
                    throw new HeronBusinessException(400, "media_asset_format_unsupported", "素材只支持 PNG/JPEG 图像");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                    || (long) width * height > MAX_PIXELS) {
                    throw new HeronBusinessException(400, "media_asset_dimensions_invalid", "素材尺寸超出允许范围");
                }
                validateContainer(bytes, format);
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw new HeronBusinessException(400, "media_asset_image_invalid", "素材首帧像素无法完整解码或尺寸不符");
                }
                decoded.flush();
                String mediaType = "PNG".equals(format) ? "image/png" : "image/jpeg";
                return new ImageInfo(format, mediaType, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new HeronBusinessException(400, "media_asset_image_invalid", "素材图像无法解析", e);
        } catch (IllegalArgumentException e) {
            throw new HeronBusinessException(400, "media_asset_image_invalid", "素材图像无法解析", e);
        }
    }

    /** ImageIO 会容忍缺失的 PNG IEND/JPEG EOI；入库前补齐容器完整性检查。 */
    private static void validateContainer(byte[] bytes, String format) {
        if ("JPEG".equals(format)) {
            if (bytes.length < 4 || (bytes[bytes.length - 2] & 0xff) != 0xff || (bytes[bytes.length - 1] & 0xff) != 0xd9) {
                throw new HeronBusinessException(400, "media_asset_image_invalid", "JPEG 文件截断或缺少结束标记");
            }
            return;
        }
        int offset = 8;
        boolean sawHeader = false;
        boolean sawEnd = false;
        while (offset < bytes.length) {
            if (offset + 12 > bytes.length) {
                throw new HeronBusinessException(400, "media_asset_image_invalid", "PNG 块被截断");
            }
            int length = Byte.toUnsignedInt(bytes[offset]) << 24 | Byte.toUnsignedInt(bytes[offset + 1]) << 16
                | Byte.toUnsignedInt(bytes[offset + 2]) << 8 | Byte.toUnsignedInt(bytes[offset + 3]);
            if (length < 0 || offset + 12L + length > bytes.length) {
                throw new HeronBusinessException(400, "media_asset_image_invalid", "PNG 块长度越界或被截断");
            }
            String type = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(bytes, offset + 4, 4 + length);
            if (Byte.toUnsignedInt(bytes[offset + 8 + length]) != (int) (crc.getValue() >>> 24)
                || Byte.toUnsignedInt(bytes[offset + 9 + length]) != (int) ((crc.getValue() >>> 16) & 0xff)
                || Byte.toUnsignedInt(bytes[offset + 10 + length]) != (int) ((crc.getValue() >>> 8) & 0xff)
                || Byte.toUnsignedInt(bytes[offset + 11 + length]) != (int) (crc.getValue() & 0xff)) {
                throw new HeronBusinessException(400, "media_asset_image_invalid", "PNG 块校验和不匹配");
            }
            if (offset == 8 && !"IHDR".equals(type)) {
                throw new HeronBusinessException(400, "media_asset_image_invalid", "PNG 缺少 IHDR 头");
            }
            sawHeader |= "IHDR".equals(type);
            offset += 12 + length;
            if ("IEND".equals(type)) {
                sawEnd = true;
                break;
            }
        }
        if (!sawHeader || !sawEnd || offset != bytes.length) {
            throw new HeronBusinessException(400, "media_asset_image_invalid", "PNG 文件截断或包含非法尾随数据");
        }
    }

    private String configKey() {
        String key = production.getOssConfigKey();
        if (key == null || key.isBlank()) {
            throw new HeronBusinessException(503, "media_asset_oss_not_configured", "素材尚未配置 OSS");
        }
        return key;
    }

    private record ImageInfo(String format, String mediaType, int width, int height) { }

    public record Stored(String configKey, String key, String url, long size, String sha256,
                        String mediaType, int width, int height) { }
}

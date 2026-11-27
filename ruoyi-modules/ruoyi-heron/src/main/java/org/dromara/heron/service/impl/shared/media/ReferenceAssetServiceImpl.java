package org.dromara.heron.service.impl.shared.media;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronReferenceAsset;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.domain.bo.ReferenceAssetQueryBo;
import org.dromara.heron.domain.vo.ReferenceAssetVo;
import org.dromara.heron.mapper.HeronReferenceAssetMapper;
import org.dromara.heron.mapper.HeronTrendVideoMapper;
import org.dromara.heron.service.IReferenceAssetService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 参考素材状态机：热榜候选 → 许可检查/人工授权 → 受控下载 → OSS 定稿。
 *
 * <p>同一素材同时只允许一个下载：单实例信号量控制并发，素材行租约控制重试窗口；
 * 失败会写回原因码并回收已上传对象，总容量超过 reference-max-total-bytes 时拒绝新下载。
 */
@Slf4j
@Service
public class ReferenceAssetServiceImpl implements IReferenceAssetService {

    private final HeronReferenceAssetMapper assets;
    private final HeronTrendVideoMapper videos;
    private final ReferenceVideoDownloader downloader;
    private final ReferenceAssetStorage storage;
    private final HeronNewsProperties news;
    private final ObjectProvider<Clock> clocks;
    private final Semaphore downloads;

    public ReferenceAssetServiceImpl(HeronReferenceAssetMapper assets, HeronTrendVideoMapper videos,
                                     ReferenceVideoDownloader downloader, ReferenceAssetStorage storage,
                                     HeronNewsProperties news, ObjectProvider<Clock> clocks) {
        this.assets = assets;
        this.videos = videos;
        this.downloader = downloader;
        this.storage = storage;
        this.news = news;
        this.clocks = clocks;
        // shortcut: 并发上限按单实例 semaphore，多副本部署需换 Redis 信号量。
        this.downloads = new Semaphore(Math.max(1, news.getReferenceMaxConcurrent()), true);
    }

    @Override
    public void registerFromTrend(List<HeronTrendVideo> candidates) {
        for (HeronTrendVideo candidate : candidates) {
            if (!eligible(candidate)) {
                continue;
            }
            if (assets.findByPlatformAndAweme(candidate.getPlatform(), candidate.getAwemeId()) != null) {
                continue;
            }
            HeronReferenceAsset row = new HeronReferenceAsset();
            row.setId(IdWorker.getId());
            row.setTrendId(candidate.getId());
            row.setPlatform(candidate.getPlatform());
            row.setAwemeId(candidate.getAwemeId());
            row.setStatus(HeronReferenceAsset.STATUS_DISCOVERED);
            row.setLicenseStatus(HeronReferenceAsset.LICENSE_UNKNOWN);
            row.setAttemptCount(0);
            row.setReservedBytes(0L);
            try {
                assets.insert(row);
            } catch (DuplicateKeyException e) {
                continue;
            }
            check(row.getId());
        }
    }

    @Override
    public PageResult<ReferenceAssetVo> queryPageList(ReferenceAssetQueryBo bo, PageQuery pageQuery) {
        var wrapper = QueryBuilder.lambda(HeronReferenceAsset.class)
            .eqIfText(HeronReferenceAsset::getPlatform, bo.getPlatform())
            .eqIfText(HeronReferenceAsset::getAwemeId, bo.getAwemeId())
            .eqIfText(HeronReferenceAsset::getStatus, bo.getStatus())
            .eqIfText(HeronReferenceAsset::getLicenseStatus, bo.getLicenseStatus())
            .orderByDesc(HeronReferenceAsset::getCreateTime).build();
        var page = assets.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public ReferenceAssetVo queryById(long assetId) {
        ReferenceAssetVo vo = assets.selectVoById(assetId);
        if (vo == null) {
            throw new HeronBusinessException(404, "reference_asset_not_found", "参考素材不存在：" + assetId);
        }
        return vo;
    }

    @Override
    public ReferenceAssetVo check(long assetId) {
        HeronReferenceAsset asset = required(assetId);
        if (List.of(HeronReferenceAsset.STATUS_PENDING_AUTH, HeronReferenceAsset.STATUS_RESTRICTED,
            HeronReferenceAsset.STATUS_ACQUIRING, HeronReferenceAsset.STATUS_ACQUIRED).contains(asset.getStatus())) {
            return queryById(assetId);
        }
        LocalDateTime now = now();
        if (assets.markChecking(assetId, now) != 1) {
            throw new HeronBusinessException(409, "reference_asset_not_checkable", "参考素材当前状态不能检查");
        }
        try {
            requireCandidate(asset);
        } catch (HeronBusinessException e) {
            log.info("参考素材检查未通过 assetId={} code={} reason={}", assetId, e.getCode(), e.getMessage());
            assets.markFailed(assetId, e.getCode(), truncate(e.getMessage()), now);
            return queryById(assetId);
        }
        if (HeronReferenceAsset.LICENSE_UNKNOWN.equals(asset.getLicenseStatus())) {
            assets.markPendingAuth(assetId, "来源许可未知，需人工授权后才能下载", now);
            return queryById(assetId);
        }
        if (HeronReferenceAsset.LICENSE_RESTRICTED.equals(asset.getLicenseStatus())) {
            assets.authorizeRestricted(assetId, asset.getAuthorizationReason(), actor(asset), now);
            return queryById(assetId);
        }
        assets.markDiscovered(assetId, now);
        return acquire(assetId);
    }

    @Override
    public ReferenceAssetVo authorize(long assetId, boolean allowed, String reason) {
        String note = reason == null ? "" : reason.trim();
        if (note.isEmpty()) {
            throw new HeronBusinessException(400, "invalid_argument", "授权或限制参考素材必须填写理由");
        }
        required(assetId);
        LocalDateTime now = now();
        int updated = allowed
            ? assets.authorizeAllowed(assetId, note, currentUserId(), now)
            : assets.authorizeRestricted(assetId, note, currentUserId(), now);
        if (updated != 1) {
            throw new HeronBusinessException(409, "reference_asset_not_authorizable", "参考素材当前状态不能授权");
        }
        return allowed ? acquire(assetId) : queryById(assetId);
    }

    @Override
    public ReferenceAssetVo acquire(long assetId) {
        HeronReferenceAsset asset = required(assetId);
        if (HeronReferenceAsset.STATUS_ACQUIRED.equals(asset.getStatus())) {
            return queryById(assetId);
        }
        if (!HeronReferenceAsset.LICENSE_ALLOWED.equals(asset.getLicenseStatus())) {
            throw new HeronBusinessException(409, "reference_auth_required", "参考素材尚未获得下载授权");
        }
        LocalDateTime now = now();
        if (HeronReferenceAsset.STATUS_ACQUIRING.equals(asset.getStatus())) {
            if (asset.getLeaseUntil() == null || asset.getLeaseUntil().isAfter(now)) {
                throw new HeronBusinessException(409, "reference_download_running", "参考素材正在下载或租约已变化");
            }
        } else if (!List.of(HeronReferenceAsset.STATUS_DISCOVERED, HeronReferenceAsset.STATUS_FAILED).contains(asset.getStatus())) {
            throw new HeronBusinessException(409, "reference_asset_not_acquirable", "参考素材当前状态不能下载");
        }
        HeronTrendVideo candidate = requireCandidate(asset);
        if (!tryAcquireDownload()) {
            throw new HeronBusinessException(429, "reference_download_busy", "参考素材下载并发已满，请稍后重试");
        }
        String token = IdUtil.fastSimpleUUID();
        long reserved = Math.max(1, news.getReferenceMaxBytes());
        ReferenceAssetStorage.Stored stored = null;
        boolean claimed = false;
        boolean finalized = false;
        try {
            int updated = assets.claim(assetId, token, now,
                now.plusSeconds(Math.max(1, news.getReferenceLeaseSeconds())), reserved);
            if (updated != 1) {
                throw new HeronBusinessException(409, "reference_download_running", "参考素材正在下载或租约已变化");
            }
            claimed = true;
            asset.setRunToken(token);
            if (assets.usedBytes() > Math.max(1, news.getReferenceMaxTotalBytes())) {
                throw new HeronBusinessException(429, "reference_capacity_exceeded", "参考素材总容量已满");
            }
            try (ReferenceVideoDownloader.Downloaded file = downloader.download(candidate.getDownloadUrl())) {
                stored = storage.upload(asset, file);
                storage.register(asset, stored);
            }
            int finished = assets.finalizeAcquired(assetId, token, stored.key(), stored.url(), stored.size(),
                stored.sha256(), stored.mediaType(), now());
            if (finished != 1) {
                throw new HeronBusinessException(409, "reference_lease_lost", "下载租约已失效，本次结果不能定稿");
            }
            finalized = true;
            return queryById(assetId);
        } catch (RuntimeException e) {
            if (claimed && !finalized) {
                if (stored != null) {
                    cleanup(asset, stored);
                }
                recordFailure(assetId, token, e);
            }
            throw e;
        } finally {
            downloads.release();
        }
    }

    private HeronReferenceAsset required(long assetId) {
        HeronReferenceAsset asset = assets.selectById(assetId);
        if (asset == null) {
            throw new HeronBusinessException(404, "reference_asset_not_found", "参考素材不存在：" + assetId);
        }
        return asset;
    }

    private HeronTrendVideo requireCandidate(HeronReferenceAsset asset) {
        HeronTrendVideo candidate = videos.selectById(asset.getTrendId());
        if (candidate == null) {
            throw new HeronBusinessException(409, "reference_candidate_missing", "来源热榜候选已不存在");
        }
        if (candidate.getDurationMs() == null) {
            throw new HeronBusinessException(409, "reference_duration_unknown", "来源视频时长未知，不能自动下载");
        }
        long max = news.getTrendMaxDurationMs();
        if (max > 0 && candidate.getDurationMs() > max) {
            throw new HeronBusinessException(409, "reference_candidate_too_long", "来源视频超过拆解时长上限");
        }
        if (candidate.getDownloadUrl() == null || candidate.getDownloadUrl().isBlank()) {
            throw new HeronBusinessException(409, "reference_url_missing", "来源视频没有可用下载直链");
        }
        return candidate;
    }

    private boolean eligible(HeronTrendVideo candidate) {
        if (candidate == null || candidate.getId() == null || candidate.getDurationMs() == null) {
            return false;
        }
        long max = news.getTrendMaxDurationMs();
        return max <= 0 || candidate.getDurationMs() <= max;
    }

    private boolean tryAcquireDownload() {
        try {
            return downloads.tryAcquire(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HeronBusinessException(503, "reference_download_interrupted", "参考素材下载被中断");
        }
    }

    private void recordFailure(long assetId, String token, RuntimeException error) {
        try {
            assets.recordFailure(assetId, token, code(error), truncate(reason(error)), now());
        } catch (RuntimeException e) {
            log.error("参考素材失败原因写回失败 assetId={}", assetId, e);
        }
    }

    private void cleanup(HeronReferenceAsset asset, ReferenceAssetStorage.Stored stored) {
        try {
            storage.delete(asset, stored);
        } catch (RuntimeException e) {
            log.warn("参考素材失败对象回收失败 assetId={} key={} cause={}", asset.getId(),
                stored.key(), e.getMessage());
        }
    }

    private static long actor(HeronReferenceAsset asset) {
        return asset.getAuthorizedBy() == null ? 0L : asset.getAuthorizedBy();
    }

    private static long currentUserId() {
        try {
            Long userId = LoginHelper.getUserId();
            return userId == null ? 0L : userId;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static String code(RuntimeException error) {
        return error instanceof HeronBusinessException business ? business.getCode() : "reference_acquire_failed";
    }

    private static String reason(RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private LocalDateTime now() {
        Clock clock = clocks.getIfAvailable();
        return LocalDateTime.now(clock == null ? Clock.systemDefaultZone() : clock);
    }
}

package org.dromara.heron.mapper;

import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronReferenceAsset;
import org.dromara.heron.domain.vo.ReferenceAssetVo;

import java.time.LocalDateTime;
import java.util.List;

/** 参考素材持久层：所有执行写回都带状态或租约令牌条件。 */
public interface HeronReferenceAssetMapper extends BaseMapperPlus<HeronReferenceAsset, ReferenceAssetVo> {

    default HeronReferenceAsset findByPlatformAndAweme(String platform, String awemeId) {
        return selectOne(QueryBuilder.lambda(HeronReferenceAsset.class)
            .eq(HeronReferenceAsset::getPlatform, platform)
            .eq(HeronReferenceAsset::getAwemeId, awemeId)
            .last("LIMIT 1").build());
    }

    default int markChecking(long assetId, LocalDateTime now) {
        return lambda().set(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_CHECKING)
            .setSql("failure_code = NULL").setSql("failure_reason = NULL")
            .set(HeronReferenceAsset::getUpdateTime, now)
            .eq(HeronReferenceAsset::getId, assetId)
            .in(HeronReferenceAsset::getStatus, List.of(HeronReferenceAsset.STATUS_DISCOVERED,
                HeronReferenceAsset.STATUS_FAILED)).updateCount();
    }

    default int markPendingAuth(long assetId, String reason, LocalDateTime now) {
        return lambda().set(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_PENDING_AUTH)
            .set(HeronReferenceAsset::getFailureCode, "reference_auth_required")
            .set(HeronReferenceAsset::getFailureReason, reason)
            .set(HeronReferenceAsset::getUpdateTime, now)
            .eq(HeronReferenceAsset::getId, assetId)
            .eq(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_CHECKING).updateCount();
    }

    default int markDiscovered(long assetId, LocalDateTime now) {
        return lambda().set(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_DISCOVERED)
            .setSql("failure_code = NULL").setSql("failure_reason = NULL")
            .set(HeronReferenceAsset::getUpdateTime, now)
            .eq(HeronReferenceAsset::getId, assetId)
            .in(HeronReferenceAsset::getStatus, List.of(HeronReferenceAsset.STATUS_CHECKING,
                HeronReferenceAsset.STATUS_PENDING_AUTH, HeronReferenceAsset.STATUS_FAILED)).updateCount();
    }

    default int markFailed(long assetId, String code, String reason, LocalDateTime now) {
        return lambda().set(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_FAILED)
            .set(HeronReferenceAsset::getFailureCode, code)
            .set(HeronReferenceAsset::getFailureReason, reason)
            .set(HeronReferenceAsset::getUpdateTime, now)
            .eq(HeronReferenceAsset::getId, assetId)
            .eq(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_CHECKING).updateCount();
    }

    default int authorizeAllowed(long assetId, String reason, long actor, LocalDateTime now) {
        return lambda().set(HeronReferenceAsset::getLicenseStatus, HeronReferenceAsset.LICENSE_ALLOWED)
            .set(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_DISCOVERED)
            .set(HeronReferenceAsset::getAuthorizationReason, reason)
            .set(HeronReferenceAsset::getAuthorizedBy, actor)
            .set(HeronReferenceAsset::getAuthorizedAt, now)
            .setSql("failure_code = NULL").setSql("failure_reason = NULL")
            .set(HeronReferenceAsset::getUpdateTime, now)
            .eq(HeronReferenceAsset::getId, assetId)
            .in(HeronReferenceAsset::getStatus, List.of(HeronReferenceAsset.STATUS_PENDING_AUTH,
                HeronReferenceAsset.STATUS_DISCOVERED, HeronReferenceAsset.STATUS_FAILED)).updateCount();
    }

    default int authorizeRestricted(long assetId, String reason, long actor, LocalDateTime now) {
        return lambda().set(HeronReferenceAsset::getLicenseStatus, HeronReferenceAsset.LICENSE_RESTRICTED)
            .set(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_RESTRICTED)
            .set(HeronReferenceAsset::getAuthorizationReason, reason)
            .set(HeronReferenceAsset::getAuthorizedBy, actor)
            .set(HeronReferenceAsset::getAuthorizedAt, now)
            .set(HeronReferenceAsset::getUpdateTime, now)
            .eq(HeronReferenceAsset::getId, assetId)
            .in(HeronReferenceAsset::getStatus, List.of(HeronReferenceAsset.STATUS_CHECKING,
                HeronReferenceAsset.STATUS_PENDING_AUTH, HeronReferenceAsset.STATUS_DISCOVERED,
                HeronReferenceAsset.STATUS_FAILED, HeronReferenceAsset.STATUS_RESTRICTED)).updateCount();
    }

    default int claim(long assetId, String token, LocalDateTime now, LocalDateTime leaseUntil, long reservedBytes) {
        return lambda().set(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_ACQUIRING)
            .set(HeronReferenceAsset::getRunToken, token)
            .set(HeronReferenceAsset::getLeaseUntil, leaseUntil)
            .set(HeronReferenceAsset::getReservedBytes, reservedBytes)
            .setSql("attempt_count = attempt_count + 1")
            .setSql("failure_code = NULL").setSql("failure_reason = NULL")
            .set(HeronReferenceAsset::getUpdateTime, now)
            .eq(HeronReferenceAsset::getId, assetId)
            .eq(HeronReferenceAsset::getLicenseStatus, HeronReferenceAsset.LICENSE_ALLOWED)
            .in(HeronReferenceAsset::getStatus, List.of(HeronReferenceAsset.STATUS_DISCOVERED,
                HeronReferenceAsset.STATUS_FAILED, HeronReferenceAsset.STATUS_ACQUIRING))
            .and(w -> w.isNull(HeronReferenceAsset::getRunToken)
                .or().le(HeronReferenceAsset::getLeaseUntil, now)).updateCount();
    }

    default int finalizeAcquired(long assetId, String token, String ossKey, String ossUrl, long fileSize,
                                 String sha256, String mediaType, LocalDateTime acquiredAt) {
        return lambda().set(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_ACQUIRED)
            .set(HeronReferenceAsset::getOssKey, ossKey)
            .set(HeronReferenceAsset::getOssUrl, ossUrl)
            .set(HeronReferenceAsset::getFileSize, fileSize)
            .set(HeronReferenceAsset::getContentSha256, sha256)
            .set(HeronReferenceAsset::getMediaType, mediaType)
            .set(HeronReferenceAsset::getAcquiredAt, acquiredAt)
            .setSql("run_token = NULL").setSql("lease_until = NULL")
            .set(HeronReferenceAsset::getReservedBytes, 0L)
            .setSql("failure_code = NULL").setSql("failure_reason = NULL")
            .set(HeronReferenceAsset::getUpdateTime, acquiredAt)
            .eq(HeronReferenceAsset::getId, assetId)
            .eq(HeronReferenceAsset::getRunToken, token)
            .eq(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_ACQUIRING).updateCount();
    }

    default int recordFailure(long assetId, String token, String code, String reason, LocalDateTime failedAt) {
        return lambda().set(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_FAILED)
            .set(HeronReferenceAsset::getFailureCode, code)
            .set(HeronReferenceAsset::getFailureReason, reason)
            .setSql("run_token = NULL").setSql("lease_until = NULL")
            .set(HeronReferenceAsset::getReservedBytes, 0L)
            .set(HeronReferenceAsset::getUpdateTime, failedAt)
            .eq(HeronReferenceAsset::getId, assetId)
            .eq(HeronReferenceAsset::getRunToken, token)
            .eq(HeronReferenceAsset::getStatus, HeronReferenceAsset.STATUS_ACQUIRING).updateCount();
    }

    @Select("""
        SELECT COALESCE(SUM(CASE
            WHEN status = 'ACQUIRED' THEN file_size
            WHEN status = 'ACQUIRING' THEN reserved_bytes
            ELSE 0 END), 0)
        FROM heron_reference_asset
        """)
    long usedBytes();
}

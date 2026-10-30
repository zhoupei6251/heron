package org.dromara.heron.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.heron.domain.bo.*;
import org.dromara.heron.domain.vo.*;

import java.io.InputStream;
import java.io.IOException;

/** 原型草稿、不可变版本及人工交付；所有 ID 操作同时校验对象范围与动作。 */
public interface IProtoProjectService {
    ProtoProjectVo queryById(long projectId);
    PageResult<ProtoProjectVo> queryPageList(ProtoProjectQuery query, PageQuery page);
    ProtoProjectVo insertByBo(ProtoProjectBo bo);
    ProtoProjectVo updateByBo(long projectId, ProtoProjectBo bo);
    ProtoProjectVo archive(long projectId);
    ProductionReceiptVo generate(long projectId, ProtoGenerateBo bo);
    PageResult<ProtoVersionVo> versions(long projectId, PageQuery page);
    ProtoVersionVo version(long projectId, long versionId);
    ProtoVersionVo review(long projectId, long versionId, ProtoReviewBo bo);
    ProtoNativeVerificationVo recordNativeVerification(long projectId, long versionId, ProtoNativeVerificationBo bo);
    ProtoDeliveryVo deliver(long projectId, long versionId, ProtoDeliverBo bo);

    /** 审核预览/审核下载绝不创建交付事实。 */
    ProtoFile preview(long projectId, long versionId, String artifactId);
    ProtoFile reviewDownload(long projectId, long versionId, String artifactId);
    ProtoFile download(long projectId, long versionId, String artifactId, String rpSha256);

    /** 已鉴权且已 hash 校验的同一打开句柄；调用者负责关闭。 */
    record ProtoFile(String fileName, String mediaType, long bytes, String sha256,
                     InputStream content) implements AutoCloseable {
        @Override public void close() throws IOException { content.close(); }
    }
}

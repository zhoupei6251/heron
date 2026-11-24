package org.dromara.heron.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.domain.bo.ReferenceAssetQueryBo;
import org.dromara.heron.domain.vo.ReferenceAssetVo;

import java.util.List;

/** 参考素材治理：候选登记、授权、受控下载与 OSS 定稿。 */
public interface IReferenceAssetService {

    void registerFromTrend(List<HeronTrendVideo> videos);

    PageResult<ReferenceAssetVo> queryPageList(ReferenceAssetQueryBo bo, PageQuery pageQuery);

    ReferenceAssetVo queryById(long assetId);

    ReferenceAssetVo check(long assetId);

    ReferenceAssetVo authorize(long assetId, boolean allowed, String reason);

    ReferenceAssetVo acquire(long assetId);
}

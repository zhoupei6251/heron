package org.dromara.heron.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.heron.domain.bo.MediaAssetBo;
import org.dromara.heron.domain.bo.MediaAssetQueryBo;
import org.dromara.heron.domain.bo.MediaAssetSelectionBo;
import org.dromara.heron.domain.vo.MediaAssetSelectionVo;
import org.dromara.heron.domain.vo.MediaAssetVo;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;

/** 素材入库、治理与场景选材。 */
public interface IMediaAssetService {

    PageResult<MediaAssetVo> queryPageList(MediaAssetQueryBo bo, PageQuery pageQuery);

    MediaAssetVo queryById(long assetId);

    MediaAssetVo insertByBo(MediaAssetBo bo, MultipartFile file);

    MediaAssetVo updateByBo(MediaAssetBo bo);

    Boolean deleteWithValidByIds(Collection<Long> ids);

    MediaAssetSelectionVo selectForScene(MediaAssetSelectionBo request);
}

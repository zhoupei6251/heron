package org.dromara.heron.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.heron.domain.bo.*;
import org.dromara.heron.domain.vo.*;

/** 新闻候选、作品和人工审片业务入口。 */
public interface INewsWorkService {
    PageResult<NewsCandidateVo> candidates(NewsCandidateQuery query);
    NewsCandidateVo candidate(long candidateId);
    NewsCandidateVo addManual(NewsCandidateBo bo);
    NewsWorkVo claim(long candidateId);
    PageResult<NewsWorkVo> queryPageList(NewsWorkQuery query);
    NewsWorkVo queryById(long workId);
    NewsWorkVo saveDraft(long workId, NewsDraftBo bo);
    ProductionReceiptVo generate(long workId, NewsGenerateBo bo);
    PageResult<NewsVideoVersionVo> versions(long workId, PageQuery page);
    NewsVideoVersionVo version(long workId, long versionId);
    NewsVideoVersionVo review(long workId, long versionId, NewsReviewBo bo);
    /** 审片专用文件入口，不赋予正式下载权。 */
    NewsFileVo preview(long workId, long versionId, String artifactId);
    /** 仅返回已审且双指纹仍匹配的 MP4。调用方负责关闭返回句柄。 */
    NewsFileVo download(long workId, long versionId, String artifactId, String videoSha256);
}

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
    /** 审片队列：跨作品的待审版本，供审片中心一屏处理。 */
    PageResult<NewsVideoVersionVo> reviewQueue(PageQuery page);
    /** 发布记录：已有发布事实的版本，含沙箱与命令行提交。 */
    PageResult<NewsVideoVersionVo> published(PageQuery page);
    NewsVideoVersionVo version(long workId, long versionId);
    NewsVideoVersionVo review(long workId, long versionId, NewsReviewBo bo);
    /** 一次确认：批准即由发布适配器出回执，拒绝只落审核结论。手动模式的唯一人工动作。 */
    NewsVideoVersionVo confirm(long workId, long versionId, NewsReviewBo bo);
    /** 人工发布回执：只写已审且指纹仍匹配的版本，同一版本重复回填不产生第二条发布事实。 */
    NewsVideoVersionVo publish(long workId, long versionId, NewsPublishBo bo);

    /** 命令行发布只在提交时留痕，作品链接事后人工补一次。 */
    NewsVideoVersionVo backfillPublishUrl(long workId, long versionId, NewsPublishUrlBo bo);
    /** 审片专用文件入口，不赋予正式下载权。 */
    NewsFileVo preview(long workId, long versionId, String artifactId);
    /** 仅返回已审且双指纹仍匹配的 MP4。调用方负责关闭返回句柄。 */
    NewsFileVo download(long workId, long versionId, String artifactId, String videoSha256);
}

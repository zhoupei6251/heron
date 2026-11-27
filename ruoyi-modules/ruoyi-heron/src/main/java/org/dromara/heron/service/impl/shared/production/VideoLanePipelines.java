package org.dromara.heron.service.impl.shared.production;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.NewsScriptAuthor;
import org.dromara.heron.service.impl.lane.databoard.DatasetScriptAuthor;
import org.dromara.heron.service.impl.lane.knowledge.KnowledgeScriptAuthor;
import org.dromara.heron.service.impl.lane.trend.TrendAnalysis;
import tools.jackson.databind.json.JsonMapper;

/**
 * 四条视频产线的执行入口：复用同一个新闻管线，差异只在写稿作者、取数快照、评审输入与附加闸。
 *
 * @param video           共享的视频生产管线
 * @param articleAuthor   文稿解说线写稿器
 * @param knowledgeAuthor 知识图解线写稿器
 * @param datasetAuthor   数据看板/趋势调查线写稿器
 * @param properties      生产配置（文稿线开关、知识逐字上限等）
 */
public record VideoLanePipelines(NewsProductionPipeline video, NewsScriptAuthor articleAuthor,
                          KnowledgeScriptAuthor knowledgeAuthor, DatasetScriptAuthor datasetAuthor,
                          HeronProductionProperties properties) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    ProductionResultVo newsExplainer(ProductionRunVo run) {
        var input = video.begin(run, ProductionBizType.news_explainer);
        if (!properties.isArticleEnabled()) {
            throw new HeronBusinessException(409, "article_disabled", "文稿解说线未启用");
        }
        var review = video.reviewScript(run, articleAuthor.author(run), NewsProductionPipeline.evidenceQuotes(input.snapshot()),
            null, null, null, null, articleAuthor, "evidence-review");
        var script = NewsProductionPipeline.validateDraft(input, review, null);
        return video.deliver(run, input, review, script, null, null, null);
    }

    ProductionResultVo dataBoard(ProductionRunVo run) {
        var input = video.begin(run, ProductionBizType.data_board);
        var data = video.fetchData(run, input);
        var draft = datasetAuthor.author(run, data.fetched().json(), data.digest(), data.story());
        var review = video.reviewScript(run, draft.script(), data.dataset().path("facts").toString(), data.fetched(),
            draft.hookType(), null, data.story(), null, "editorial-review");
        var script = NewsProductionPipeline.validateDraft(input, review, data);
        return video.deliver(run, input, review, script, data, null, null);
    }

    ProductionResultVo trendInsight(ProductionRunVo run) {
        var input = video.begin(run, ProductionBizType.trend_insight);
        var data = video.fetchData(run, input);
        var trend = TrendAnalysis.analyze(data.dataset());
        var draft = datasetAuthor.authorTrend(run, data.fetched().json(), JSON.writeValueAsString(trend), data.digest());
        var review = video.reviewScript(run, draft.script(), data.dataset().path("facts").toString(), data.fetched(),
            draft.hookType(), trend, data.story(), null, "editorial-review");
        var script = NewsProductionPipeline.validateDraft(input, review, data);
        input.gates().add(NewsProductionPipeline.trendGate(trend, script, data.dataset()));
        return video.deliver(run, input, review, script, data, trend, null);
    }

    ProductionResultVo knowledgeExplainer(ProductionRunVo run) {
        var input = video.begin(run, ProductionBizType.knowledge_explainer);
        var pack = video.knowledgePack(input.snapshot());
        var entries = pack.require(NewsProductionPipeline.selecteds(input.snapshot()));
        var review = video.reviewScript(run, knowledgeAuthor.author(run), pack.evidenceJson(entries),
            null, null, null, null, knowledgeAuthor, "knowledge-review");
        var script = NewsProductionPipeline.validateDraft(input, review, null);
        String citations = pack.citationsJson(NewsProductionPipeline.sceneRefs(script));
        input.gates().add(NewsProductionPipeline.citationGate(script, citations, pack, properties.getKnowledgeVerbatimMaxChars()));
        return video.deliver(run, input, review, script, null, null, citations);
    }
}

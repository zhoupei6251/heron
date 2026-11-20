package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 趋势证据审核：判定与稿子必须对得上，红线必须由机器挡。
 *
 * <p>三条红线各自对应一组用例——区间波动不许说成趋势、推测不许写成确定事实、
 * 关键观点必须引到区间口径上。
 */
@DisplayName("趋势线：证据审核")
class TrendContractTest {

    private static ObjectNode dataset(String sha) {
        ObjectNode dataset = JsonNodeFactory.instance.objectNode();
        dataset.put("contract", "dataset/1");
        dataset.put("source_key", "sample-trend");
        dataset.put("data_sha256", sha);
        return dataset;
    }

    /** 只为文本规则构造一份自洽的判定：证据链、趋势阈值都对得上。 */
    private static ObjectNode trend(String verdict, double agreement, String sha) {
        ObjectNode trend = JsonNodeFactory.instance.objectNode();
        trend.put("contract", TrendContract.SCHEMA_VERSION);
        trend.put("verdict", verdict);
        trend.put("label", TrendAnalysis.label(verdict));
        trend.put("points", TrendAnalysis.INSUFFICIENT.equals(verdict) ? 5 : 12);
        if (!TrendAnalysis.INSUFFICIENT.equals(verdict)) {
            trend.put("sign_agreement", agreement);
            trend.put("net_change_pct", TrendAnalysis.SUSTAINED_DOWN.equals(verdict) ? -20.0 : 20.0);
            trend.putArray("segments").addObject().put("index", 0).put("median", 100.0);
        } else {
            trend.putArray("segments");
        }
        trend.put("corroboration", "single_source");
        ObjectNode primary = trend.putArray("evidence").addObject();
        primary.put("role", "primary");
        primary.put("source_key", "sample-trend");
        primary.put("data_sha256", sha);
        return trend;
    }

    private static ObjectNode script(String title, String closing, String... bodies) {
        ObjectNode script = JsonNodeFactory.instance.objectNode();
        script.put("title", title);
        script.put("closing", closing);
        var scenes = script.putArray("scenes");
        for (int index = 0; index < bodies.length; index++) {
            ObjectNode scene = scenes.addObject();
            scene.put("lead", "第" + (index + 1) + "屏");
            scene.put("body", bodies[index]);
            scene.put("duration_s", 10);
        }
        return script;
    }

    private static final String SHA = "sha-sample-trend";

    @Test
    @DisplayName("合规的趋势稿不报违例")
    void compliantScriptPasses() {
        ObjectNode trend = trend(TrendAnalysis.SUSTAINED_UP, 1.0, SHA);
        ObjectNode script = script("关注指数一路抬升",
            "以上只描述 {{facts.first.date}} 到 {{facts.last.date}} 的区间变化，不构成对下一期的预测。",
            "从 {{facts.first.date}} 的 {{facts.first.value}} 点，抬升到 {{facts.last.date}} 的 {{facts.last.value}} 点。",
            "同向点对占比超过六成，抬升不是单日脉冲。");

        assertTrue(TrendContract.violations(trend, script, dataset(SHA)).isEmpty());
    }

    @Test
    @DisplayName("区间波动说成持续上行，直接挡下")
    void rangeVerdictRejectsTrendClaim() {
        ObjectNode trend = trend(TrendAnalysis.RANGE, 0.5, SHA);
        ObjectNode script = script("关注指数持续上行",
            "区间为 {{facts.first.date}} 到 {{facts.last.date}}。", "整体持续上行。");

        List<String> violations = TrendContract.violations(trend, script, dataset(SHA));
        assertTrue(violations.toString().contains("区间波动"), violations.toString());
        assertTrue(violations.toString().contains("持续上行"), violations.toString());
    }

    @Test
    @DisplayName("样本不足连方向词都不许出现")
    void insufficientVerdictRejectsDirectionWords() {
        ObjectNode trend = trend(TrendAnalysis.INSUFFICIENT, 0.0, SHA);
        ObjectNode script = script("关注指数的趋势",
            "样本只有 {{facts.first.date}} 到 {{facts.last.date}} 这几期。", "整体还在上涨。");

        List<String> violations = TrendContract.violations(trend, script, dataset(SHA));
        assertTrue(violations.toString().contains("样本不足"), violations.toString());
        assertTrue(violations.toString().contains("趋势") || violations.toString().contains("上涨"));
    }

    @Test
    @DisplayName("判定上行却写成下行，反向断言被挡")
    void oppositeDirectionIsRejected() {
        ObjectNode trend = trend(TrendAnalysis.SUSTAINED_UP, 1.0, SHA);
        ObjectNode script = script("关注指数进入持续下行",
            "区间 {{facts.first.date}} 到 {{facts.last.date}}。", "整体一路走低。");

        List<String> violations = TrendContract.violations(trend, script, dataset(SHA));
        assertTrue(violations.toString().contains("持续下行"), violations.toString());
        assertTrue(violations.toString().contains("一路走低"), violations.toString());
    }

    @Test
    @DisplayName("推测写成确定事实被挡")
    void speculationIsRejected() {
        ObjectNode trend = trend(TrendAnalysis.SUSTAINED_UP, 1.0, SHA);
        ObjectNode script = script("关注指数还会走高",
            "区间 {{facts.first.date}} 到 {{facts.last.date}}，下期有望继续抬升。", "大概率延续。");

        List<String> violations = TrendContract.violations(trend, script, dataset(SHA));
        assertTrue(violations.toString().contains("有望"), violations.toString());
        assertTrue(violations.toString().contains("大概率"), violations.toString());
    }

    @Test
    @DisplayName("不引区间口径就没有出处，按红线挡")
    void missingAnchorIsRejected() {
        ObjectNode trend = trend(TrendAnalysis.SUSTAINED_UP, 1.0, SHA);
        ObjectNode script = script("关注指数一路抬升", "这几期抬得很稳。", "十二期里九期在涨。");

        List<String> violations = TrendContract.violations(trend, script, dataset(SHA));
        assertTrue(violations.toString().contains("区间口径"), violations.toString());
    }

    @Test
    @DisplayName("判定用的不是这份快照，先在证据链上就报错")
    void evidenceHashMismatchFailsFast() {
        ObjectNode trend = trend(TrendAnalysis.SUSTAINED_UP, 1.0, "sha-somewhere-else");
        ObjectNode script = script("关注指数一路抬升", "{{facts.first.date}} 到 {{facts.last.date}}。");

        List<String> violations = TrendContract.violations(trend, script, dataset(SHA));
        assertTrue(violations.toString().contains("快照哈希不一致"), violations.toString());
    }

    @Test
    @DisplayName("交叉核对有分歧却给单一方向结论，直接挡")
    void divergentButDirectionalIsRejected() {
        ObjectNode trend = trend(TrendAnalysis.SUSTAINED_UP, 1.0, SHA);
        trend.put("corroboration", "divergent");
        ObjectNode script = script("关注指数一路抬升", "{{facts.first.date}} 到 {{facts.last.date}}。",
            "整体仍然单边上行。");

        List<String> violations = TrendContract.violations(trend, script, dataset(SHA));
        assertTrue(violations.toString().contains("分歧"), violations.toString());
    }

    @Test
    @DisplayName("缺判定、版本不对、取值非法都在自洽性检查里报出来")
    void trendSelfConsistency() {
        assertTrue(TrendContract.violations(null, script("标题", "正文"), dataset(SHA))
            .toString().contains("缺少趋势判定"));

        ObjectNode wrongVersion = trend(TrendAnalysis.SUSTAINED_UP, 1.0, SHA);
        wrongVersion.put("contract", "trend/2");
        wrongVersion.put("verdict", "maybe_up");
        ObjectNode script = script("关注指数抬升", "{{facts.first.date}} 到 {{facts.last.date}}。");

        List<String> violations = TrendContract.violations(wrongVersion, script, dataset(SHA));
        assertTrue(violations.toString().contains("契约版本"), violations.toString());
        assertTrue(violations.toString().contains("maybe_up"), violations.toString());
    }

    @Test
    @DisplayName("单边判定但同向点对不足六成，判定本身不自洽")
    void directionalVerdictNeedsAgreement() {
        ObjectNode trend = trend(TrendAnalysis.SUSTAINED_UP, 0.4, SHA);
        ObjectNode script = script("关注指数抬升", "{{facts.first.date}} 到 {{facts.last.date}}。");

        List<String> violations = TrendContract.violations(trend, script, dataset(SHA));
        assertTrue(violations.toString().contains("同向点对占比"), violations.toString());
    }

    @Test
    @DisplayName("样本不足还带分段结论，判定不自洽")
    void insufficientWithSegmentsIsInconsistent() {
        ObjectNode trend = trend(TrendAnalysis.INSUFFICIENT, 0.0, SHA);
        trend.putArray("segments").addObject().put("index", 0).put("median", 100.0);
        ObjectNode script = script("关注指数", "{{facts.first.date}} 到 {{facts.last.date}}。");

        List<String> violations = TrendContract.violations(trend, script, dataset(SHA));
        assertTrue(violations.toString().contains("样本不足"), violations.toString());
    }

    @Test
    @DisplayName("区间口径锚点认首末日期、首末值与年份")
    void anchorRecognisesIntervalForms() {
        assertTrue(TrendContract.anchored("{{facts.first.date}} 到 {{facts.last.date}}"));
        assertTrue(TrendContract.anchored("{{facts.first.value}} 点"));
        assertTrue(TrendContract.anchored("{{facts.last.value}} 点"));
        assertTrue(TrendContract.anchored("{{facts.period.start_year}} 年起"));
        assertFalse(TrendContract.anchored("这几期"));
    }
}

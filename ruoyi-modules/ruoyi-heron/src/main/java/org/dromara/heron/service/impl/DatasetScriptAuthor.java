package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 数据看板脚本由模型写，出处三件（哈希、源、图表类型）由快照覆盖；
 * 契约不过先定向修复一次，再不过就退回，不盲目重跑。
 *
 * <p>有策划结论（story/1）时，策划结论通过提示词与 Java 对位复核进入写稿；
 * 正式旁白之前另产故事计划，冻结后内嵌到 v2 脚本，后续修复只读该计划，不重新策划。
 */
@Service
@RequiredArgsConstructor
public class DatasetScriptAuthor {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE_SYSTEM = resource("/prompt/data-board/script-contract.md");
    private static final String PLAN_SYSTEM = resource("/prompt/data-board/story-plan-contract.md");
    private static final String PLAN_STAGE = "dataset-story-plan";
    /** 趋势稿在数据看板契约之上再加一层：方向由 trend/1 说了算，推测与证据必须分开。 */
    private static final String TREND_SYSTEM = BASE_SYSTEM + "\n\n---\n\n"
        + resource("/prompt/data-board/trend-script.md");
    private static final String STAGE = "dataset-script";
    private static final String TREND_STAGE = "trend-script";
    private static final int MAX_REPAIRS = 2;

    private final OpenAiModelClient model;
    private final DatasetTitleAuthor titleAuthor;

    /**
     * 写稿结果：脚本 JSON + 最终选中标题的钩子类型。
     *
     * <p>kind 只在标题生成时存在；不在这里带出来，落库后就再也取不回来，
     * 归因只能整列算「未记录」。
     */
    public record Result(String script, String hookType) { }

    public Result author(ProductionRunVo run, String datasetJson, boolean digest) {
        return author(run, datasetJson, digest, null);
    }

    /**
     * 有策划结论时的写稿。
     *
     * <p>策划结论通过提示词与 Java 对位复核进入写稿；故事计划在旁白前冻结并内嵌 v2 脚本。
     */
    public Result author(ProductionRunVo run, String datasetJson, boolean digest, EditorialStory story) {
        JsonNode snapshot = JSON.readTree(datasetJson);
        List<DatasetTitleAuthor.Candidate> candidates = titleAuthor.candidates(run, datasetJson, "", story);
        String system = BASE_SYSTEM + "\n\n---\n\n"
            + ColumnPacks.text(snapshot.path("source_key").asString(""));
        JsonNode editorial = editorialOf(run);
        JsonNode plan = plan(run, snapshot, candidates, editorial, digest, story, null);
        List<String> titles = titlesForPlan(candidates, plan);
        JsonNode draft = withEvidence(parse(model.chat(run, STAGE, system,
            prompt(snapshot, titles, editorial, digest, story, plan))), snapshot, plan);
        List<String> violations = violations(draft, snapshot, candidates, null, story);
        for (int repair = 0; repair < MAX_REPAIRS && !violations.isEmpty(); repair++) {
            draft = withEvidence(parse(model.chat(run, STAGE + "-repair", system,
                repairPrompt(draft, violations, titles, editorial, story, plan))), snapshot, plan);
            violations = violations(draft, snapshot, candidates, null, story);
        }
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "dataset_script_contract",
                "脚本退回写稿：" + String.join("；", violations));
        }
        return new Result(JSON.writeValueAsString(draft),
            hookType(candidates, draft.path("title").asString("")));
    }

    /**
     * 趋势稿：多一份 trend/1 判定进提示词与复核。
     * 判定由机器先算出来，写稿只负责把它讲清楚；方向词与推测词在这里就被卡住，不指望模型自觉。
     */
    public Result authorTrend(ProductionRunVo run, String datasetJson, String trendJson, boolean digest) {
        JsonNode snapshot = JSON.readTree(datasetJson);
        JsonNode trend = JSON.readTree(trendJson);
        List<DatasetTitleAuthor.Candidate> candidates =
            titleAuthor.candidates(run, datasetJson, titleContext(trend));
        String system = TREND_SYSTEM + "\n\n---\n\n"
            + ColumnPacks.text(snapshot.path("source_key").asString(""));
        JsonNode editorial = editorialOf(run);
        JsonNode plan = plan(run, snapshot, candidates, editorial, digest, null, trend);
        List<String> titles = titlesForPlan(candidates, plan);
        JsonNode draft = withEvidence(parse(model.chat(run, TREND_STAGE, system,
            trendPrompt(snapshot, trend, titles, editorial, digest, plan))), snapshot, plan);
        List<String> violations = trendViolations(draft, snapshot, trend, candidates);
        for (int repair = 0; repair < MAX_REPAIRS && !violations.isEmpty(); repair++) {
            draft = withEvidence(parse(model.chat(run, TREND_STAGE + "-repair", system,
                trendRepairPrompt(draft, violations, titles, editorial, trend, plan))), snapshot, plan);
            violations = trendViolations(draft, snapshot, trend, candidates);
        }
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "trend_script_contract",
                "趋势稿退回写稿：" + String.join("；", violations));
        }
        return new Result(JSON.writeValueAsString(draft),
            hookType(candidates, draft.path("title").asString("")));
    }

    /** 趋势稿的定向修复：趋势判定与方向口径一并带回，改稿不许把结论改没。 */
    public Result repairTrend(ProductionRunVo run, String datasetJson, String trendJson,
                              String scriptJson, List<String> issues) {
        JsonNode snapshot = JSON.readTree(datasetJson);
        JsonNode trend = JSON.readTree(trendJson);
        List<DatasetTitleAuthor.Candidate> candidates =
            titleAuthor.candidates(run, datasetJson, titleContext(trend));
        String system = TREND_SYSTEM + "\n\n---\n\n"
            + ColumnPacks.text(snapshot.path("source_key").asString(""));
        JsonNode original = JSON.readTree(scriptJson);
        JsonNode plan = frozenPlan(original, snapshot);
        List<String> titles = titlesForPlan(candidates, plan);
        JsonNode repaired = withEvidence(parse(model.chat(run, TREND_STAGE + "-repair", system,
            trendRepairPrompt(original, issues, titles, editorialOf(run), trend, plan))), snapshot, plan);
        List<String> violations = trendViolations(repaired, snapshot, trend, candidates);
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "trend_script_contract",
                "趋势稿退回修复：" + String.join("；", violations));
        }
        return new Result(JSON.writeValueAsString(repaired),
            hookType(candidates, repaired.path("title").asString("")));
    }

    private static String titleContext(JsonNode trend) {
        return "本期趋势判定：\n" + TrendAnalysis.summary(trend)
            + "\n标题要落在「变化意味着什么」，不要复述快照数值。";
    }

    private static String trendPrompt(JsonNode snapshot, JsonNode trend, List<String> titles,
                                      JsonNode editorial, boolean digest, JsonNode plan) {
        StringBuilder text = new StringBuilder();
        if (digest) {
            text.append("这是一条「今日数据速览」，不是热点解读，开头点明这是例行盘点。\n");
        }
        appendEditorial(text, editorial);
        appendFrozenPlan(text, plan);
        text.append("机器趋势判定（方向以它为准，不许改写）：\n")
            .append(TrendAnalysis.summary(trend)).append('\n')
            .append("trend/1：\n").append(trend.toString()).append('\n')
            .append("数据快照：\n").append(snapshot).append('\n')
            .append("标题候选（title 必须与下面某一项完全一致，原样复制）：\n");
        for (String title : titles) {
            text.append("- ").append(title).append('\n');
        }
        return text.append("按契约输出完整脚本 JSON。旁白只能用 {{facts.*}} 或 {{trend.*}} 引用实测值，")
            .append("正文不得出现任何阿拉伯数字；不许写「预计」「将会」「有望」一类推测词。").toString();
    }

    private static String trendRepairPrompt(JsonNode script, List<String> violations, List<String> titles,
                                            JsonNode editorial, JsonNode trend, JsonNode plan) {
        StringBuilder text = new StringBuilder()
            .append("上一版趋势稿没有通过校验，问题如下：\n")
            .append("- ").append(String.join("\n- ", violations)).append('\n')
            .append("机器趋势判定（方向以它为准，不许改写）：\n")
            .append(TrendAnalysis.summary(trend)).append('\n');
        appendEditorial(text, editorial);
        appendFrozenPlan(text, plan);
        return text.append("title 只能从这些候选里原样选一个：\n")
            .append("- ").append(String.join("\n- ", titles)).append('\n')
            .append("只修改不符合要求的字段，其余字段原样保留，输出完整 JSON。\n")
            .append("上一版脚本：\n").append(script.toString()).toString();
    }

    private static List<String> trendViolations(JsonNode script, JsonNode snapshot, JsonNode trend,
                                                List<DatasetTitleAuthor.Candidate> candidates) {
        List<String> out = new java.util.ArrayList<>(violations(script, snapshot, candidates, trend, null));
        out.addAll(TrendContract.violations(trend, script, snapshot));
        return out;
    }

    /** 审稿不过时的定向修复：复用同一套修复提示与契约复核，只换掉问题清单。 */
    public Result repair(ProductionRunVo run, String datasetJson, String scriptJson, List<String> issues) {
        return repair(run, datasetJson, scriptJson, issues, null);
    }

    /** 有策划结论时的定向修复：策划结论与对位问题一起带回，改稿不许把故事改没。 */
    public Result repair(ProductionRunVo run, String datasetJson, String scriptJson, List<String> issues,
                         EditorialStory story) {
        JsonNode snapshot = JSON.readTree(datasetJson);
        List<DatasetTitleAuthor.Candidate> candidates = titleAuthor.candidates(run, datasetJson, "", story);
        String system = BASE_SYSTEM + "\n\n---\n\n"
            + ColumnPacks.text(snapshot.path("source_key").asString(""));
        JsonNode original = JSON.readTree(scriptJson);
        JsonNode plan = frozenPlan(original, snapshot);
        List<String> titles = titlesForPlan(candidates, plan);
        JsonNode repaired = withEvidence(parse(model.chat(run, STAGE + "-repair", system,
            repairPrompt(original, issues, titles, editorialOf(run), story, plan))), snapshot, plan);
        List<String> violations = violations(repaired, snapshot, candidates, null, story);
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "dataset_script_contract",
                "脚本退回修复：" + String.join("；", violations));
        }
        return new Result(JSON.writeValueAsString(repaired),
            hookType(candidates, repaired.path("title").asString("")));
    }

    /** 选中标题对应的钩子类型；标题不在候选里（理论上不会）返回 null，不猜。 */
    static String hookType(List<DatasetTitleAuthor.Candidate> candidates, String title) {
        return candidates.stream()
            .filter(candidate -> candidate.title().equals(title))
            .map(DatasetTitleAuthor.Candidate::kind)
            .findFirst()
            .orElse(null);
    }

    /**
     * 编辑决策只认冻结输入：重试、修复、换阶段都必须读到同一份 intent / reason / angle，
     * 不能因为报错重跑就退回成一条没有编辑意图的通用稿。
     * 老快照没有 editorial 节点时返回缺失节点，提示词里按“未给出”处理。
     */
    static JsonNode editorialOf(ProductionRunVo run) {
        if (run == null || run.inputJson() == null || run.inputJson().isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode input = JSON.readTree(run.inputJson());
            JsonNode editorial = input.path("editorial");
            return editorial.isObject() ? editorial : JSON.createObjectNode();
        } catch (RuntimeException e) {
            return JSON.createObjectNode();
        }
    }

    private static List<String> violations(JsonNode script, JsonNode snapshot,
                                         List<DatasetTitleAuthor.Candidate> candidates,
                                         JsonNode trend, EditorialStory story) {
        List<String> titles = candidates.stream().map(DatasetTitleAuthor.Candidate::title).toList();
        List<String> violations = new java.util.ArrayList<>(DatasetScriptContract.violations(script, snapshot));
        // 分镜也要进修复循环：分镜不合规时渲染闸会直接退回整条片子，
        // 与其出片前才发现，不如在写稿阶段就修一次。
        JsonNode resolved;
        try {
            // 趋势稿的 {{trend.*}} 只有带上趋势判定才解析得动
            resolved = trend == null ? DatasetPlaceholders.resolve(script, snapshot)
                : DatasetPlaceholders.resolve(script, snapshot, trend);
        } catch (RuntimeException e) {
            resolved = script;
        }
        violations.addAll(StoryboardContract.violations(script, resolved));
        if (!titles.contains(script.path("title").asString(""))) {
            violations.add("title 必须从候选里原样选一个");
        }
        JsonNode plan = script.path("story_plan");
        if (plan.isObject()) {
            String selectedKind = hookType(candidates, script.path("title").asString(""));
            if (!plan.path("hookType").asString("").equals(selectedKind)) {
                violations.add("选中标题的 hookType 与冻结故事计划不一致");
            }
        }
        // 策划结论的对位：开场讲张力、中段一条主张一段、结尾讲落点、引用的路径不出信封
        violations.addAll(StoryScriptAlignment.violations(script, story, script.path("title").asString("")));
        return List.copyOf(new java.util.LinkedHashSet<>(violations));
    }

    /** 快照事实覆盖模型自述，模型只负责旁白；v2 同时内嵌系统冻结的故事计划。 */
    private static ObjectNode withEvidence(JsonNode draft, JsonNode snapshot, JsonNode plan) {
        ObjectNode script = (ObjectNode) draft;
        script.put("dataset_sha256", snapshot.path("data_sha256").asString(""));
        script.put("source_key", snapshot.path("source_key").asString(""));
        script.put("chart", DatasetScriptContract.chartFor(snapshot.path("shape").asString("")));
        String attribution = snapshot.path("license").path("attribution").asString("");
        script.put("source_name", attribution.isBlank() ? snapshot.path("source_name").asString("") : attribution);
        if (plan != null) {
            script.put("schema_version", "2");
            script.set("story_plan", plan.deepCopy());
            JsonNode scenes = script.path("scenes");
            if (scenes.isArray()) {
                for (int index = 0; index < scenes.size(); index++) {
                    if (scenes.get(index).isObject()) {
                        ((ObjectNode) scenes.get(index)).put("scene_id", String.format("scene_%03d", index + 1));
                    }
                }
            }
        } else {
            script.remove("schema_version");
            script.remove("story_plan");
        }
        return script;
    }

    private static String prompt(JsonNode snapshot, List<String> titles, JsonNode editorial, boolean digest,
                                 EditorialStory story, JsonNode plan) {
        StringBuilder text = new StringBuilder();
        if (digest) {
            text.append("这是一条「今日数据速览」，不是热点解读，开头点明这是例行盘点。\n");
        }
        appendEditorial(text, editorial);
        appendStory(text, story);
        appendFrozenPlan(text, plan);
        text.append("数据快照：\n").append(snapshot).append('\n')
            .append("标题候选（title 必须与下面某一项完全一致，原样复制）：\n");
        for (String title : titles) {
            text.append("- ").append(title).append('\n');
        }
        return text.append("按契约输出完整脚本 JSON。旁白只能用 {{facts.<路径>}} 引用数字，正文不得出现任何阿拉伯数字。")
            .toString();
    }

    private static String repairPrompt(JsonNode script, List<String> violations, List<String> titles,
                                       JsonNode editorial, EditorialStory story, JsonNode plan) {
        StringBuilder text = new StringBuilder()
            .append("上一版脚本没有通过契约校验，问题如下：\n")
            .append("- ").append(String.join("\n- ", violations)).append('\n');
        appendEditorial(text, editorial);
        appendStory(text, story);
        appendFrozenPlan(text, plan);
        return text.append("title 只能从这些候选里原样选一个：\n")
            .append("- ").append(String.join("\n- ", titles)).append('\n')
            .append("只修改不符合要求的字段，其余字段原样保留，输出完整 JSON。\n")
            .append("超长的字段压到上限以下并留 10 字余量，不要贴着上限写；宁可短，不能被裁。\n")
            .append("上一版脚本：\n").append(script.toString()).toString();
    }

    /** 冻结计划选定钩子后，写稿只看到同类型标题，避免模型选一个不相容标题再反复修复。 */
    private static List<String> titlesForPlan(List<DatasetTitleAuthor.Candidate> candidates, JsonNode plan) {
        if (plan == null) return candidates.stream().map(DatasetTitleAuthor.Candidate::title).toList();
        String hook = plan.path("hookType").asString("");
        return candidates.stream().filter(candidate -> candidate.kind().equals(hook))
            .map(DatasetTitleAuthor.Candidate::title).toList();
    }

    /** 先产计划、复核并冻结，再允许模型写旁白；计划不合格不进入写稿阶段。 */
    private JsonNode plan(ProductionRunVo run, JsonNode snapshot, List<DatasetTitleAuthor.Candidate> candidates,
                          JsonNode editorial, boolean digest, EditorialStory story, JsonNode trend) {
        JsonNode draft = freezePlan(parsePlan(model.chat(run, PLAN_STAGE, PLAN_SYSTEM,
            planPrompt(snapshot, candidates, editorial, digest, story, trend))), snapshot);
        List<String> violations = planViolations(draft, snapshot, candidates, story);
        for (int repair = 0; repair < MAX_REPAIRS && !violations.isEmpty(); repair++) {
            StringBuilder prompt = new StringBuilder("上一版故事计划未通过校验：\n- ")
                .append(String.join("\n- ", violations)).append('\n');
            appendStory(prompt, story);
            draft = freezePlan(parsePlan(model.chat(run, PLAN_STAGE + "-repair", PLAN_SYSTEM,
                prompt.append("只修计划，不写旁白。\n上一版计划：\n").append(draft).toString())), snapshot);
            violations = planViolations(draft, snapshot, candidates, story);
        }
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "dataset_story_plan_contract",
                "故事计划退回：" + String.join("；", violations));
        }
        return freezePlan(draft, snapshot);
    }

    private static List<String> planViolations(JsonNode plan, JsonNode snapshot,
                                               List<DatasetTitleAuthor.Candidate> candidates, EditorialStory story) {
        List<String> items = new java.util.ArrayList<>(StoryPlanContract.planViolations(plan, snapshot));
        items.addAll(StoryPlanContract.storyPlanViolations(plan, story));
        String hook = plan.path("hookType").asString("");
        if (candidates.stream().noneMatch(candidate -> candidate.kind().equals(hook))) {
            items.add("hookType 不在标题候选类型中");
        }
        return List.copyOf(new java.util.LinkedHashSet<>(items));
    }

    private static JsonNode parsePlan(String text) {
        try {
            return parse(text);
        } catch (RuntimeException e) {
            throw new HeronBusinessException(409, "dataset_story_plan_contract",
                "故事计划退回：模型输出不是 JSON 对象");
        }
    }

    private static JsonNode freezePlan(JsonNode plan, JsonNode snapshot) {
        ObjectNode frozen = (ObjectNode) plan.deepCopy();
        frozen.put("schemaVersion", StoryPlanContract.SCHEMA_VERSION);
        frozen.put("datasetSha256", snapshot.path("data_sha256").asString(""));
        return frozen;
    }

    private static String planPrompt(JsonNode snapshot, List<DatasetTitleAuthor.Candidate> candidates,
                                     JsonNode editorial, boolean digest, EditorialStory story, JsonNode trend) {
        StringBuilder text = new StringBuilder();
        if (digest) text.append("这是今日数据速览，计划应体现例行盘点与信息密度。\n");
        appendEditorial(text, editorial);
        appendStory(text, story);
        if (trend != null) {
            text.append("机器趋势判定（方向不许改写）：\n").append(TrendAnalysis.summary(trend)).append('\n')
                .append(trend).append('\n');
        }
        text.append("标题候选（hookType 必须与后续选中标题的类型一致）：\n");
        for (DatasetTitleAuthor.Candidate candidate : candidates) {
            text.append("- [").append(candidate.kind()).append("] ").append(candidate.title()).append('\n');
        }
        return text.append("冻结数据快照：\n").append(snapshot).append('\n')
            .append("只输出故事计划 JSON，不写旁白。").toString();
    }

    /** 修复 v2 时只读原脚本里的冻结计划；v1 旧稿没有计划，保持旧契约。 */
    private static JsonNode frozenPlan(JsonNode script, JsonNode snapshot) {
        if (!"2".equals(script.path("schema_version").asString(""))) return null;
        JsonNode plan = script.path("story_plan");
        List<String> violations = StoryPlanContract.planViolations(plan, snapshot);
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "dataset_story_plan_contract",
                "冻结故事计划不可用于修复：" + String.join("；", violations));
        }
        return plan.deepCopy();
    }

    private static void appendFrozenPlan(StringBuilder text, JsonNode plan) {
        if (plan == null) return;
        text.append("已冻结故事计划（逐屏对位，不得改写；每屏旁白必须消费全部 evidenceRefs，")
            .append("beat 与 scene_type 必须分别等于计划的 beat 与 expectedVisual.sceneType）：\n")
            .append(plan).append('\n');
    }

    /**
     * 编辑决策是这次选这个题、这个角度的理由，写稿必须接住：
     * 只把 intent/reason/angle 当记录不留进提示，模型就会写成一条和今天为什么发它无关的通用稿。
     * 修复也带同一份，避免改稿时把编辑意图改没。
     */
    private static void appendEditorial(StringBuilder text, JsonNode editorial) {
        if (editorial == null || !editorial.isObject()) return;
        String intent = editorial.path("editorialIntent").asString("").strip();
        String reason = editorial.path("reason").asString("").strip();
        String angle = editorial.path("storyAngle").asString("").strip();
        JsonNode signals = editorial.path("opportunitySignals");
        boolean hasOpportunity = !editorial.path("opportunityHeadline").asString("").strip().isBlank()
            || (signals.isObject() && !signals.isEmpty());
        if (intent.isBlank() && reason.isBlank() && angle.isBlank() && !hasOpportunity) return;
        text.append("编辑意图（必须体现在旁白里，不能只讲过数据）：")
            .append(intent.isBlank() ? "（未给出）" : intent).append('\n');
        if (!reason.isBlank()) text.append("为什么今天选它：").append(reason).append('\n');
        if (!angle.isBlank()) text.append("故事视角：").append(angle).append('\n');
        appendOpportunity(text, editorial);
    }

    /**
     * 已定故事：写稿必须照着讲，不许自己另起一套结论。
     *
     * <p>三件事说清：故事讲的是什么、哪条是推测（要写成推测口吻）、段落顺序怎么排。
     * 可用的数字路径只给信封里那些，出了信封的数字一律不许引用。
     */
    private static void appendStory(StringBuilder text, EditorialStory story) {
        if (story == null) return;
        text.append("已定故事（策划已定，照着讲，不许另起结论）：\n");
        text.append("- 一句话：").append(story.oneLine()).append('\n');
        if (story.tension() != null) {
            text.append("- 张力：").append(story.tension().statement()).append('\n');
        }
        if (story.payoff() != null) {
            text.append("- 落点：").append(story.payoff().text()).append('\n');
        }
        text.append("- 主张列表：\n");
        for (EditorialStory.Claim claim : story.claims()) {
            String tag = "fact".equals(claim.kind()) ? "事实" : "假设";
            text.append("  ").append(claim.id()).append('[').append(tag).append("]：")
                .append(claim.text()).append('\n');
        }
        if (!story.assumptions().isEmpty()) {
            text.append("- 假设必须写成推测口吻，不许写成断言：\n");
            for (String assumption : story.assumptions()) {
                text.append("  ").append(assumption).append('\n');
            }
        }
        text.append("- 可用的数字路径只有这些：")
            .append(String.join("、", story.allEvidenceRefs())).append('\n');
        text.append("- 段落顺序：第 1 段讲张力（不复述标题），中间每段讲一条主张，结尾讲落点。\n");
    }

    /**
     * 选题当时的判断：机会的头线与信号。
     *
     * <p>只当背景与角度参考，数字与时间口径都不许进旁白——旁白里的数字只能来自占位符，
     * 区间口径只能来自 {@code facts.first.date} 到 {@code facts.last.date}。
     * 选题台看的是短期变化，片子讲的是快照区间：两个口径混在一起，审稿会判成把区间说成单日。
     */
    private static void appendOpportunity(StringBuilder text, JsonNode editorial) {
        String headline = editorial.path("opportunityHeadline").asString("").strip();
        JsonNode signals = editorial.path("opportunitySignals");
        boolean hasSignals = signals.isObject() && !signals.isEmpty();
        if (headline.isBlank() && !hasSignals) {
            return;
        }
        text.append("选题当时的判断（只作背景与角度参考：其中的数字与时间口径都不许写进旁白，")
            .append("旁白数字一律用占位符，区间以 facts.first.date 到 facts.last.date 为准）：\n");
        if (!headline.isBlank()) {
            text.append("- 头线：").append(headline).append('\n');
        }
        if (hasSignals) {
            text.append("- 信号：").append(signals.toString()).append('\n');
        }
    }

    private static JsonNode parse(String text) {
        JsonNode node = text == null ? null : JSON.readTree(ModelJson.unwrap(text));
        if (node == null || !node.isObject()) {
            throw new HeronBusinessException(409, "dataset_script_contract", "脚本退回写稿：模型输出不是 JSON 对象");
        }
        return node;
    }

    private static String resource(String path) {
        try (InputStream stream = DatasetScriptAuthor.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("缺少提示词资源 " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}

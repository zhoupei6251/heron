package org.dromara.heron.service.impl.lane.databoard;

import org.dromara.heron.domain.EditorialStory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * story/1 的硬校验与生产期复核。
 *
 * <p>纯函数：喂一份 story 与一份快照，得出「哪几条不合格」。不出网、不读库、不调模型，
 * 所以单测里直接喂 JSON 就能把每条规则钉住。
 *
 * <p>校验分两段：
 * <ul>
 *   <li>{@link #violations(EditorialStory, JsonNode)} 策划期——用规划快照检查故事本身；</li>
 *   <li>{@link #evidenceViolations(EditorialStory, JsonNode)} 生产期——用生产快照复核引用、方向与覆盖。</li>
 * </ul>
 */
public final class DatasetStoryContract {

    /** 六种叙事类型。模型只能在这个集合里选，选别的由 {@link #violations} 拒。 */
    public static final Set<String> NARRATIVES = Set.of(
        "counter_intuitive", "numeric_contrast", "trend_reversal",
        "rank_competition", "time_change", "problem_evidence_reveal");

    /** 叙事 → 允许的张力类型。张力讲的不是这一类故事，就是套模板。 */
    public static final Map<String, Set<String>> TENSION_KINDS = Map.of(
        "counter_intuitive", Set.of("reversal", "outlier"),
        "numeric_contrast", Set.of("gap"),
        "trend_reversal", Set.of("reversal", "trend_break"),
        "rank_competition", Set.of("rank_swap", "gap"),
        "time_change", Set.of("trend_break", "gap"),
        "problem_evidence_reveal", Set.of("question", "outlier"));

    /** 叙事 → 允许的标题钩子。钩子与故事不搭，片子开头就出戏。 */
    public static final Map<String, Set<String>> TITLE_KINDS = Map.of(
        "counter_intuitive", Set.of("反常识", "疑问悬念"),
        "numeric_contrast", Set.of("数字反差", "利益相关"),
        "trend_reversal", Set.of("疑问悬念", "反常识"),
        "rank_competition", Set.of("数字反差", "利益相关"),
        "time_change", Set.of("时间紧迫", "数字反差"),
        "problem_evidence_reveal", Set.of("疑问悬念", "数字反差"));

    /** 张力类型的全集。 */
    public static final Set<String> TENSION_ALL = Set.of(
        "reversal", "gap", "rank_swap", "trend_break", "outlier", "question");
    /** 主角类型：指标本身、单实体、群体。 */
    public static final Set<String> PROTAGONIST_KINDS = Set.of("metric", "entity", "group");
    /** 主张类型：事实与假设。假设不得带出处——没出处就不能当事实讲。 */
    public static final Set<String> CLAIM_KINDS = Set.of("fact", "assumption");
    /** 选题来源：机会、路由、速览。 */
    public static final Set<String> SOURCE_KINDS = Set.of("opportunity", "router", "digest");

    public static final int MIN_CLAIMS = 3;
    public static final int MAX_CLAIMS = 6;
    public static final int MIN_FACT_CLAIMS = 2;
    public static final int MAX_ASSUMPTIONS = 3;
    /** 证据信封：去重后全部引用路径的上限。窄信封压表达，但宽信封等于没有约束。 */
    public static final int MAX_ENVELOPE = 8;

    private static final Set<String> ABSOLUTE_WORDS = Set.of(
        "第一", "最高", "最低", "唯一", "全部", "全球");
    private static final List<String> DAILY_WORDS = List.of(
        "较前一日", "昨日", "单日", "一天内");
    /** 说出「大家」等于没说出谁受影响。 */
    private static final Set<String> GENERIC_AUDIENCE = Set.of(
        "大家", "所有人", "公众", "每个人", "大众", "群众", "受众");
    /** 因果断言用词：事实主张里出现就说明它其实是个假设。 */
    private static final List<String> CAUSAL_WORDS = List.of(
        "因为", "导致", "由于", "说明");

    private DatasetStoryContract() {
    }

    /**
     * 策划期硬校验：故事本身是否成立。
     *
     * @param story    模型给出的策划结论
     * @param snapshot 规划期取的那份快照（引用要能在它里面解析）
     */
    public static List<String> violations(EditorialStory story, JsonNode snapshot) {
        List<String> items = new ArrayList<>();
        if (story == null) {
            return List.of("story 为空");
        }
        if (!EditorialStory.SCHEMA_VERSION.equals(story.schemaVersion())) {
            items.add("schemaVersion 不是 " + EditorialStory.SCHEMA_VERSION);
        }
        String narrative = story.narrative();
        if (narrative == null || !NARRATIVES.contains(narrative)) {
            items.add("narrative 不在六类之内: " + narrative);
            narrative = null;
        }
        items.addAll(text(items, "oneLine", story.oneLine(), 40, snapshot));

        EditorialStory.Protagonist protagonist = story.protagonist();
        if (protagonist == null) {
            items.add("protagonist 缺失");
        } else {
            if (protagonist.kind() == null || !PROTAGONIST_KINDS.contains(protagonist.kind())) {
                items.add("protagonist.kind 不是 metric/entity/group: " + protagonist.kind());
            }
            items.addAll(text(items, "protagonist.label", protagonist.label(), 20, snapshot));
            items.addAll(entityRef(items, protagonist, snapshot));
        }

        EditorialStory.Tension tension = story.tension();
        if (tension == null) {
            items.add("tension 缺失");
        } else {
            if (tension.kind() == null || !TENSION_ALL.contains(tension.kind())) {
                items.add("tension.kind 不在六类之内: " + tension.kind());
            } else if (narrative != null) {
                Set<String> allowed = TENSION_KINDS.getOrDefault(narrative, Set.of());
                if (!allowed.contains(tension.kind())) {
                    items.add("tension.kind " + tension.kind() + " 与叙事 " + narrative + " 不相容");
                }
            }
            items.addAll(text(items, "tension.statement", tension.statement(), 60, snapshot));
            items.addAll(refs(items, "tension.evidenceRefs", tension.evidenceRefs(), 1, 4, snapshot));
        }

        List<EditorialStory.Claim> claims = story.claims();
        if (claims.size() < MIN_CLAIMS || claims.size() > MAX_CLAIMS) {
            items.add("claims 数量必须在 " + MIN_CLAIMS + "–" + MAX_CLAIMS + " 之间: " + claims.size());
        }
        Set<String> claimIds = new LinkedHashSet<>();
        Set<String> assumptionTexts = new LinkedHashSet<>();
        int factCount = 0;
        for (EditorialStory.Claim claim : claims) {
            String id = claim.id() == null ? "" : claim.id();
            if (!id.matches("c[1-6]")) {
                items.add("claim id 不合法: " + id);
            } else if (!claimIds.add(id)) {
                items.add("claim id 重复: " + id);
            }
            items.addAll(text(items, "claim " + id + ".text", claim.text(), 60, snapshot));
            if (!CLAIM_KINDS.contains(claim.kind())) {
                items.add("claim " + id + ".kind 不是 fact/assumption: " + claim.kind());
            } else if ("fact".equals(claim.kind())) {
                factCount++;
                items.addAll(refs(items, "claim " + id + ".evidenceRefs", claim.evidenceRefs(), 1, 4, snapshot));
                items.addAll(causal(items, "claim " + id + ".text", claim.text()));
            } else {
                assumptionTexts.add(claim.text() == null ? "" : claim.text());
                if (!claim.evidenceRefs().isEmpty()) {
                    items.add("claim " + id + " 是假设，不该带 evidenceRefs");
                }
            }
        }
        if (factCount < MIN_FACT_CLAIMS) {
            items.add("事实主张不足 " + MIN_FACT_CLAIMS + " 条");
        }

        EditorialStory.Payoff payoff = story.payoff();
        if (payoff == null) {
            items.add("payoff 缺失");
        } else {
            items.addAll(text(items, "payoff.text", payoff.text(), 60, snapshot));
            items.addAll(refs(items, "payoff.evidenceRefs", payoff.evidenceRefs(), 1, 3, snapshot));
        }

        EditorialStory.Stakes stakes = story.stakes();
        if (stakes == null) {
            items.add("stakes 缺失");
        } else {
            items.addAll(text(items, "stakes.audience", stakes.audience(), 40, snapshot));
            items.addAll(text(items, "stakes.impact", stakes.impact(), 60, snapshot));
            if (stakes.audience() != null && GENERIC_AUDIENCE.contains(stakes.audience().strip())) {
                items.add("stakes.audience 太泛，说不出谁受影响");
            }
            items.addAll(banned(items, "stakes.audience", stakes.audience()));
            items.addAll(banned(items, "stakes.impact", stakes.impact()));
        }

        List<String> assumptions = story.assumptions();
        if (assumptions.size() > MAX_ASSUMPTIONS) {
            items.add("assumptions 超过 " + MAX_ASSUMPTIONS + " 条: " + assumptions.size());
        }
        if (!new LinkedHashSet<>(assumptions).equals(assumptionTexts)) {
            items.add("assumptions 与 claims 里的假设主张不是一一对应");
        }

        items.addAll(basis(items, story.basis()));

        Set<String> envelope = new LinkedHashSet<>(story.allEvidenceRefs());
        if (envelope.size() > MAX_ENVELOPE) {
            items.add("证据信封超过 " + MAX_ENVELOPE + " 条: " + envelope.size());
        }

        items.addAll(direction(items, story));
        items.addAll(period(items, story, snapshot));
        items.addAll(absolutes(items, story, snapshot));
        return List.copyOf(new LinkedHashSet<>(items));
    }

    /**
     * 生产期复核：拿生产快照回头验策划时引用的东西还站不站得住。
     *
     * <p>只查三个粗粒度不变量——引用还能不能解析、方向有没有翻转、覆盖有没有塌陷。
     * 规划快照与生产快照本就不是同一份，逐点比对没有意义。
     */
    public static List<String> evidenceViolations(EditorialStory story, JsonNode production) {
        List<String> items = new ArrayList<>();
        if (story == null) {
            return List.of("story 为空");
        }
        if (production == null || !production.isObject()) {
            return List.of("生产快照缺失");
        }
        int index = 0;
        for (String ref : story.allEvidenceRefs()) {
            index++;
            if (!resolves(production, ref)) {
                items.add("story 第 " + index + " 条 evidenceRef " + ref + " 在生产快照里解析不到");
            }
        }
        Double planned = story.basis() == null ? null : story.basis().changePct();
        Double actual = productionChangePct(production);
        if (planned != null && actual != null && planned != 0 && actual != 0
            && (planned > 0) != (actual > 0)) {
            items.add("story 方向在生产快照里翻转：策划 " + planned + "，生产 " + actual);
        }
        Integer plannedPoints = story.basis() == null ? null : story.basis().points();
        JsonNode rows = production.path("rows");
        if (plannedPoints != null && plannedPoints > 0 && rows.isArray()) {
            int produced = rows.size();
            if (produced < Math.ceil(plannedPoints * 0.5)) {
                items.add("story 覆盖塌陷：策划 " + plannedPoints + " 点，生产只有 " + produced + " 点");
            }
        }
        Boolean extreme = story.basis() == null ? null : story.basis().extreme();
        if (Boolean.TRUE.equals(extreme) && !extremeNow(production)) {
            items.add("story 的极值结论在生产快照里已经不成立");
        }
        return List.copyOf(new LinkedHashSet<>(items));
    }

    /**
     * 这份数据能讲哪几种叙事。
     *
     * <p>前提由机器算，不由模型声称：模型只能在返回的集合里选，选不到就是没有故事。
     */
    public static Set<String> availableNarratives(JsonNode snapshot, DatasetSignal.Signal signal) {
        Set<String> out = new LinkedHashSet<>();
        if (snapshot == null || !snapshot.isObject()) {
            return out;
        }
        String shape = snapshot.path("shape").asString("");
        JsonNode facts = snapshot.path("facts");
        if ("geo".equals(shape)) {
            // geo 必须 3D，而 problem_evidence_reveal 只有 2D 调查风格承接——给了也出不了片
            if (facts.path("count").asInt(0) >= 2 && spread(facts) > 0) {
                out.add("numeric_contrast");
            }
            return out;
        }
        if (signal == null) {
            return out;
        }
        Double zscore = signal.zscore();
        Double change = signal.changePct();
        double scale = signal.changeScale();
        if (signal.extreme() || (zscore != null && Math.abs(zscore) >= 2.0)) {
            out.add("counter_intuitive");
        }
        if ((change != null && scale > 0 && Math.abs(change) >= scale) || spread(facts) > 0) {
            out.add("numeric_contrast");
        }
        if (("wide".equals(shape) || "long".equals(shape)) && trendReversed(snapshot)) {
            out.add("trend_reversal");
        }
        if ("rows".equals(shape) || ("long".equals(shape) && entitiesClose(facts))) {
            out.add("rank_competition");
        }
        if (("wide".equals(shape) || "long".equals(shape)) && periodMoved(facts)) {
            out.add("time_change");
        }
        out.add("problem_evidence_reveal");
        return out;
    }

    /** 叙事类型对应的标题钩子集合；叙事不在六类之内时返回空集。 */
    public static Set<String> titleKinds(String narrative) {
        return TITLE_KINDS.getOrDefault(narrative, Set.of());
    }

    /** 文本类字段：非空、不过长、无裸数字、占位符可解析。 */
    private static List<String> text(List<String> items, String field, String value, int max, JsonNode snapshot) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            result.add(field + " 为空");
            return result;
        }
        if (value.codePointCount(0, value.length()) > max) {
            result.add(field + " 超过 " + max + " 字");
        }
        for (String violation : DatasetContract.narrationViolations(snapshot, value)) {
            if ("旁白为空".equals(violation)) {
                continue;
            }
            if (violation.startsWith("占位符无法解析")) {
                result.add(field + " " + violation);
            } else if ("旁白正文含裸数字".equals(violation)) {
                result.add(field + " 含裸数字");
            }
        }
        return result;
    }

    /** 引用路径：条数区间 + 必须指向 facts 下可解析的标量。 */
    private static List<String> refs(List<String> items, String field, List<String> refs, int min, int max,
                                    JsonNode snapshot) {
        List<String> result = new ArrayList<>();
        if (refs == null || refs.size() < min) {
            result.add(field + " 至少 " + min + " 条");
            return result;
        }
        if (refs.size() > max) {
            result.add(field + " 超过 " + max + " 条");
        }
        for (String ref : refs) {
            if (ref == null || !ref.startsWith("facts.")) {
                result.add(field + " 的引用必须以 facts. 开头: " + ref);
                continue;
            }
            if (!resolves(snapshot, ref)) {
                result.add(field + " 的引用解析不到: " + ref);
            }
        }
        return result;
    }

    /** 主角的实体引用：非 metric 主角必须指得出一个对象。 */
    private static List<String> entityRef(List<String> items, EditorialStory.Protagonist protagonist, JsonNode snapshot) {
        List<String> result = new ArrayList<>();
        String ref = protagonist.entityRef();
        if (ref == null || ref.isBlank()) {
            if (!"metric".equals(protagonist.kind())) {
                result.add("protagonist.entityRef 不能为空");
            }
            return result;
        }
        if (!ref.startsWith("facts.")) {
            result.add("protagonist.entityRef 必须以 facts. 开头: " + ref);
            return result;
        }
        JsonNode node = DatasetFacts.at(snapshot, ref);
        if (node == null || node.isNull()) {
            result.add("protagonist.entityRef 解析不到: " + ref);
        }
        return result;
    }

    /** 策划依据：来源、哈希、口径。 */
    private static List<String> basis(List<String> items, EditorialStory.Basis basis) {
        List<String> result = new ArrayList<>();
        if (basis == null) {
            result.add("basis 缺失");
            return result;
        }
        if (basis.sourceKind() == null || !SOURCE_KINDS.contains(basis.sourceKind())) {
            result.add("basis.sourceKind 不是 opportunity/router/digest: " + basis.sourceKind());
        } else if ("opportunity".equals(basis.sourceKind()) && basis.opportunityId() == null) {
            result.add("basis.opportunityId 不能为空");
        }
        String hash = basis.planningDataSha256();
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            result.add("basis.planningDataSha256 不是完整 SHA-256");
        }
        if (basis.points() == null || basis.points() <= 0) {
            result.add("basis.points 缺失或非正");
        }
        if (basis.coverage() == null || basis.coverage() < 0 || basis.coverage() > 1) {
            result.add("basis.coverage 必须在 0–1 之间");
        }
        return result;
    }

    /** 方向一致：故事里说涨说跌，必须和机器算出的方向同号。 */
    private static List<String> direction(List<String> items, EditorialStory story) {
        List<String> result = new ArrayList<>();
        Double change = story.basis() == null ? null : story.basis().changePct();
        if (change == null || change == 0) {
            return result;
        }
        boolean up = change > 0;
        result.addAll(direction(items, "tension.statement", story.tension() == null ? null : story.tension().statement(), up));
        for (EditorialStory.Claim claim : story.claims()) {
            // 假设本就是推测，允许与当前方向相反；方向闸只管事实主张。
            if (!"fact".equals(claim.kind())) {
                continue;
            }
            result.addAll(direction(items, "claim " + claim.id() + ".text", claim.text(), up));
        }
        result.addAll(direction(items, "payoff.text", story.payoff() == null ? null : story.payoff().text(), up));
        return result;
    }

    private static List<String> direction(List<String> items, String field, String text, boolean up) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        boolean saysUp = containsAny(text, "涨", "升", "走高", "新高", "反弹", "上行", "增长", "提高");
        boolean saysDown = containsAny(text, "跌", "降", "走低", "新低", "回落", "下行", "下滑", "减少");
        if (saysUp && !up) {
            result.add(field + " 的方向与数据不符（数据在跌，文本在说涨）");
        }
        if (saysDown && up) {
            result.add(field + " 的方向与数据不符（数据在涨，文本在说跌）");
        }
        return result;
    }

    /** 时间口径：把区间变化说成单日变化，与 dataset 脚本契约同口径。 */
    private static List<String> period(List<String> items, EditorialStory story, JsonNode snapshot) {
        List<String> result = new ArrayList<>();
        String shape = snapshot.path("shape").asString("");
        if (!"wide".equals(shape) && !"long".equals(shape)) {
            return result;
        }
        List<String> texts = new ArrayList<>();
        texts.add(story.tension() == null ? null : story.tension().statement());
        for (EditorialStory.Claim claim : story.claims()) {
            texts.add(claim.text());
        }
        texts.add(story.payoff() == null ? null : story.payoff().text());
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (String word : DAILY_WORDS) {
                if (text.contains(word)) {
                    result.add("时间口径：把区间变化说成单日变化（" + word + "）");
                    return result;
                }
            }
        }
        return result;
    }

    /** 绝对词：只有单期分布且覆盖满的时候才准说。 */
    private static List<String> absolutes(List<String> items, EditorialStory story, JsonNode snapshot) {
        List<String> result = new ArrayList<>();
        String shape = snapshot.path("shape").asString("");
        Double coverage = story.basis() == null ? null : story.basis().coverage();
        boolean shapeOk = "rows".equals(shape) || "geo".equals(shape);
        boolean coverageOk = coverage != null && coverage >= 1.0;
        List<String> texts = new ArrayList<>();
        texts.add(story.tension() == null ? null : story.tension().statement());
        for (EditorialStory.Claim claim : story.claims()) {
            texts.add(claim.text());
        }
        texts.add(story.payoff() == null ? null : story.payoff().text());
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (String word : ABSOLUTE_WORDS) {
                if (text.contains(word) && !(shapeOk && coverageOk)) {
                    result.add("绝对词「" + word + "」没有单期满覆盖支撑");
                    return result;
                }
            }
        }
        return result;
    }

    /** 禁语：投资建议与预测。复用标题那一份，不另造一套。 */
    private static List<String> banned(List<String> items, String field, String text) {
        List<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String word : DatasetTitleAuthor.BANNED) {
            if (text.contains(word)) {
                result.add(field + " 含禁语: " + word);
            }
        }
        return result;
    }

    /** 因果断言：事实主张只陈述事实，讲因果必须降级成假设。 */
    private static List<String> causal(List<String> items, String field, String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        for (String word : CAUSAL_WORDS) {
            if (text.contains(word)) {
                result.add(field + " 不能断言因果（" + word + "），要讲因果请降级成假设");
                return result;
            }
        }
        return result;
    }

    /** 引用能否在快照里解析到标量。 */
    private static boolean resolves(JsonNode snapshot, String ref) {
        if (snapshot == null || ref == null || !ref.startsWith("facts.")) {
            return false;
        }
        JsonNode node = DatasetFacts.at(snapshot, ref);
        return node != null && !node.isNull() && (node.isNumber() || node.isTextual());
    }

    /** 生产快照里的变化率：wide 取整体，long 取变化最剧烈的一支。 */
    private static Double productionChangePct(JsonNode production) {
        JsonNode facts = production.path("facts");
        String shape = production.path("shape").asString("");
        if ("wide".equals(shape)) {
            JsonNode value = facts.path("change_pct");
            return value.isNumber() ? value.asDouble() : null;
        }
        if ("long".equals(shape)) {
            JsonNode series = facts.path("series");
            Double best = null;
            for (JsonNode item : series) {
                JsonNode value = item.path("change_pct");
                if (!value.isNumber()) {
                    continue;
                }
                double current = value.asDouble();
                if (best == null || Math.abs(current) > Math.abs(best)) {
                    best = current;
                }
            }
            return best;
        }
        return null;
    }

    /** 生产快照里「极值仍在末点」是否还成立。 */
    private static boolean extremeNow(JsonNode production) {
        JsonNode facts = production.path("facts");
        String shape = production.path("shape").asString("");
        if ("wide".equals(shape)) {
            double last = facts.path("last").path("value").asDouble(0);
            double max = facts.path("max").path("value").asDouble(0);
            double min = facts.path("min").path("value").asDouble(0);
            return last >= max - 1e-9 || last <= min + 1e-9;
        }
        if ("long".equals(shape)) {
            JsonNode series = facts.path("series");
            if (!series.isArray() || series.isEmpty()) {
                return false;
            }
            double max = Double.NEGATIVE_INFINITY;
            double min = Double.POSITIVE_INFINITY;
            boolean hit = false;
            for (JsonNode item : series) {
                double last = item.path("last").asDouble(0);
                max = Math.max(max, last);
                min = Math.min(min, last);
            }
            for (JsonNode item : series) {
                double last = item.path("last").asDouble(0);
                if (last >= max - 1e-9 || last <= min + 1e-9) {
                    hit = true;
                }
            }
            return hit;
        }
        return false;
    }

    /** 分布跨度：rows/geo 用它判断有没有「数字反差」可讲。 */
    private static double spread(JsonNode facts) {
        JsonNode value = facts.path("spread");
        if (value.isNumber()) {
            return value.asDouble();
        }
        JsonNode items = facts.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return 0;
        }
        double max = Double.NEGATIVE_INFINITY;
        double min = Double.POSITIVE_INFINITY;
        for (JsonNode item : items) {
            double current = item.path("value").asDouble(0);
            max = Math.max(max, current);
            min = Math.min(min, current);
        }
        return max - min;
    }

    /** 趋势反转：近端方向与基线方向相反。 */
    private static boolean trendReversed(JsonNode snapshot) {
        JsonNode rows = snapshot.path("rows");
        if (!rows.isArray() || rows.size() < 8) {
            return false;
        }
        List<Double> values = new ArrayList<>();
        for (JsonNode row : rows) {
            int index = "long".equals(snapshot.path("shape").asString("")) ? 2 : 1;
            if (!row.isArray() || row.size() <= index) {
                return false;
            }
            JsonNode cell = row.get(index);
            if (!cell.isNumber()) {
                return false;
            }
            values.add(cell.asDouble());
        }
        int split = values.size() - DatasetSignal.SHORT_WINDOW;
        if (split <= 1) {
            return false;
        }
        double baselineFrom = values.get(0);
        double baselineTo = values.get(split - 1);
        double recentFrom = values.get(split - 1);
        double recentTo = values.get(values.size() - 1);
        double baseline = baselineTo - baselineFrom;
        double recent = recentTo - recentFrom;
        if (baseline == 0 || recent == 0) {
            return false;
        }
        return (baseline > 0) != (recent > 0);
    }

    /** 排名竞争：领跑者的末值距全场最大值不足分布跨度的一成。 */
    private static boolean entitiesClose(JsonNode facts) {
        JsonNode series = facts.path("series");
        if (!series.isArray() || series.size() < 2) {
            return false;
        }
        double max = Double.NEGATIVE_INFINITY;
        double min = Double.POSITIVE_INFINITY;
        for (JsonNode item : series) {
            double last = item.path("last").asDouble(0);
            max = Math.max(max, last);
            min = Math.min(min, last);
        }
        double span = max - min;
        if (span <= 0) {
            return false;
        }
        JsonNode leader = facts.path("leader");
        JsonNode lastLeader = leader.path("value");
        return lastLeader.isNumber() && Math.abs(lastLeader.asDouble() - max) <= span * 0.1;
    }

    /** 时间变化：区间跨了年份。 */
    private static boolean periodMoved(JsonNode facts) {
        JsonNode period = facts.path("period");
        int start = period.path("start_year").asInt(0);
        int end = period.path("end_year").asInt(0);
        return start > 0 && end > 0 && start != end;
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}

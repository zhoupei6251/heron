package org.dromara.heron.domain;

import org.dromara.common.json.utils.JsonUtils;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * story/1：数据看板线一条片子的「策划结论」。
 *
 * <p>取数、写标题、写旁白之间原本缺一层「这条片子讲的是什么事」。story 补的就是这一层：
 * 谁是主角、张力在哪、主张有哪几条、落点是什么、谁受影响。结论随冻结快照一起落盘，
 * 写稿时读同一份，所以「策划说什么」和「片子讲什么」能逐条对得上。
 *
 * <p>三条铁律写在结构里：
 * <ul>
 *   <li>数值只走 {@code facts.*} 占位符，模型不写数字——所以每一处引用都指向数据本身；</li>
 *   <li>叙事类型由机器前提决定（见 {@code DatasetStoryContract}），模型只能在允许集合里选；</li>
 *   <li>脚本 JSON 不加字段，story 只通过提示词与 Java 复核两条通道进写稿。</li>
 * </ul>
 *
 * <p>{@code basis} 是唯一允许自由扩展的容器：将来加信号字段不用升版本。
 */
public record EditorialStory(
        String schemaVersion,
        String narrative,
        String oneLine,
        Protagonist protagonist,
        Tension tension,
        List<Claim> claims,
        Payoff payoff,
        Stakes stakes,
        List<String> assumptions,
        Basis basis) {

    /** story 契约版本；与冻结快照的 schemaVersion 无关，各自演进。 */
    public static final String SCHEMA_VERSION = "story/1";

    public EditorialStory {
        claims = claims == null ? List.of() : List.copyOf(claims);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    }

    /** 主角：这条片子讲的是谁或什么。metric 表示指标本身，entity/group 表示主体。 */
    public record Protagonist(String kind, String label, String entityRef) { }

    /** 张力：这片子的「不对劲」在哪，是全片的钩子来源。 */
    public record Tension(String kind, String statement, List<String> evidenceRefs) {
        public Tension {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    /** 一条主张。fact 必须有出处，assumption 是明确标注的推测。 */
    public record Claim(String id, String text, String kind, List<String> evidenceRefs) {
        public Claim {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    /** 落点：张力最后落到什么结论上。 */
    public record Payoff(String text, List<String> evidenceRefs) {
        public Payoff {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    /** 谁受影响、影响在哪。说不出「谁」的故事不算故事。 */
    public record Stakes(String audience, String impact) { }

    /** 策划当时的机器依据：信号、口径、哈希。用于生产期复核，不进旁白。 */
    public record Basis(String signalType,
                        Double changePct,
                        Double zscore,
                        Boolean extreme,
                        Double coverage,
                        Integer points,
                        String periodStart,
                        String periodEnd,
                        String planningDataSha256,
                        String sourceKind,
                        Long opportunityId) { }

    /** 模型输出转 story；字段缺失一律留空，由契约闸报缺什么，不在这里猜。 */
    public static EditorialStory fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode protagonist = node.path("protagonist");
        JsonNode tension = node.path("tension");
        JsonNode payoff = node.path("payoff");
        JsonNode stakes = node.path("stakes");
        JsonNode basis = node.path("basis");
        return new EditorialStory(
            text(node, "schemaVersion"),
            text(node, "narrative"),
            text(node, "oneLine"),
            new Protagonist(text(protagonist, "kind"), text(protagonist, "label"), text(protagonist, "entityRef")),
            new Tension(text(tension, "kind"), text(tension, "statement"), strings(tension, "evidenceRefs")),
            claims(node.path("claims")),
            new Payoff(text(payoff, "text"), strings(payoff, "evidenceRefs")),
            new Stakes(text(stakes, "audience"), text(stakes, "impact")),
            strings(node, "assumptions"),
            new Basis(text(basis, "signalType"),
                number(basis, "changePct"),
                number(basis, "zscore"),
                bool(basis, "extreme"),
                number(basis, "coverage"),
                integer(basis, "points"),
                text(basis, "periodStart"),
                text(basis, "periodEnd"),
                text(basis, "planningDataSha256"),
                text(basis, "sourceKind"),
                longValue(basis, "opportunityId")));
    }

    /** 冻结快照里的落盘形态：键名与 fromJson 一一对应，读方不认识就当没有 story。 */
    public Map<String, Object> toMap() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("schemaVersion", schemaVersion);
        node.put("narrative", narrative);
        node.put("oneLine", oneLine);
        Map<String, Object> who = new LinkedHashMap<>();
        who.put("kind", protagonist == null ? null : protagonist.kind());
        who.put("label", protagonist == null ? null : protagonist.label());
        who.put("entityRef", protagonist == null ? null : protagonist.entityRef());
        node.put("protagonist", who);
        Map<String, Object> why = new LinkedHashMap<>();
        why.put("kind", tension == null ? null : tension.kind());
        why.put("statement", tension == null ? null : tension.statement());
        why.put("evidenceRefs", tension == null ? List.of() : tension.evidenceRefs());
        node.put("tension", why);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Claim claim : claims) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", claim.id());
            item.put("text", claim.text());
            item.put("kind", claim.kind());
            item.put("evidenceRefs", claim.evidenceRefs());
            items.add(item);
        }
        node.put("claims", items);
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("text", payoff == null ? null : payoff.text());
        end.put("evidenceRefs", payoff == null ? List.of() : payoff.evidenceRefs());
        node.put("payoff", end);
        Map<String, Object> who2 = new LinkedHashMap<>();
        who2.put("audience", stakes == null ? null : stakes.audience());
        who2.put("impact", stakes == null ? null : stakes.impact());
        node.put("stakes", who2);
        node.put("assumptions", assumptions);
        Map<String, Object> why2 = new LinkedHashMap<>();
        if (basis != null) {
            why2.put("signalType", basis.signalType());
            why2.put("changePct", basis.changePct());
            why2.put("zscore", basis.zscore());
            why2.put("extreme", basis.extreme());
            why2.put("coverage", basis.coverage());
            why2.put("points", basis.points());
            why2.put("periodStart", basis.periodStart());
            why2.put("periodEnd", basis.periodEnd());
            why2.put("planningDataSha256", basis.planningDataSha256());
            why2.put("sourceKind", basis.sourceKind());
            why2.put("opportunityId", basis.opportunityId());
        }
        node.put("basis", why2);
        return node;
    }

    /** 落盘用的 JSON 文本。 */
    public String toJson() {
        return JsonUtils.toJsonString(toMap());
    }

    /**
     * 用机器算出的依据替换模型给的依据。
     *
     * <p>策划依据不接受模型自述：信号类型、方向、z-score、覆盖、点数、区间与哈希都由 Java 算，
     * 模型写什么都不作数。顺带把 schemaVersion 定死——版本是契约的事，不是模型的事。
     */
    public EditorialStory withBasis(Basis basis) {
        return new EditorialStory(SCHEMA_VERSION, narrative, oneLine, protagonist, tension,
            claims, payoff, stakes, assumptions, basis);
    }

    /** 全部引用路径：tension、claims、payoff 的 evidenceRefs 合起来就是「信封」。 */
    public List<String> allEvidenceRefs() {
        List<String> refs = new ArrayList<>();
        if (tension != null) {
            refs.addAll(tension.evidenceRefs());
        }
        for (Claim claim : claims) {
            refs.addAll(claim.evidenceRefs());
        }
        if (payoff != null) {
            refs.addAll(payoff.evidenceRefs());
        }
        return refs;
    }

    /** 事实型主张：只有它们要求旁白给出处。 */
    public List<Claim> factClaims() {
        return claims.stream().filter(claim -> "fact".equals(claim.kind())).toList();
    }

    private static List<Claim> claims(JsonNode array) {
        List<Claim> items = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return items;
        }
        for (JsonNode node : array) {
            items.add(new Claim(text(node, "id"), text(node, "text"), text(node, "kind"), strings(node, "evidenceRefs")));
        }
        return items;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asString("");
    }

    private static List<String> strings(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        JsonNode array = node.path(field);
        if (!array.isArray()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : array) {
            items.add(item.asString(""));
        }
        return items;
    }

    private static Double number(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return null;
        }
        return value.asDouble();
    }

    private static Integer integer(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return null;
        }
        return value.asInt();
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return null;
        }
        return value.asLong();
    }

    private static Boolean bool(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            return null;
        }
        return value.asBoolean();
    }
}

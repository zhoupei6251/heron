package org.dromara.heron.service.impl.shared.research;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.service.impl.shared.support.ModelJson;
import org.dromara.heron.service.impl.shared.support.OpenAiModelClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 风格蒸馏：当日技法卡 → 候选 Profile 资产，学习闭环的最后一步。
 *
 * <p>学习链原本止步于技法卡（docs/learned/&lt;date&gt;/）：拆解和归纳都有了，
 * 但结论落不进生产——新视觉风格还是要人从零写 Profile。本服务把这一步接上：
 * 读当日全部技法卡的 frontmatter，一次 LLM 蒸馏成一套候选视觉语言的
 * tokens.css + profile.json + skeleton-rules.json，写入 data/profiles/&lt;id&gt;-draft/。
 *
 * <p>人工过闸靠目录命名：候选目录带 -draft 后缀，ProfileRegistry.load 对
 * 未登记目录照旧拒绝（select 只认线内词汇），审核通过后改名去掉 -draft、
 * 在 ProfileRegistry.select 登记线内映射，才真正进生产线。蒸馏不越权——
 * 它只能造候选，不能改现有 Profile，不碰注册表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendDistillService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MIN_CARDS = 1;
    private static final int MAX_STYLE_ID = 60;

    private final TrendLearnDocuments documents;
    private final OpenAiModelClient model;
    private final HeronProductionProperties properties;

    /** 蒸馏产物：候选目录与其中的三个契约文件。 */
    public record Draft(Path directory, String styleId, int cards) { }

    /**
     * 把某日全部技法卡蒸馏成一套候选视觉 Profile，写入 {@code data/profiles/<styleId>-draft/}。
     * 只产候选：不改现有 Profile、不动注册表，人工审核后才可能进生产线。
     *
     * @param styleId 候选风格 id，只允许小写字母、数字与连字符
     * @return 候选目录、风格 id 与参与蒸馏的卡片数
     * @throws HeronBusinessException 400 {@code trend_distill_id_invalid} id 不合法；
     *         409 {@code trend_distill_no_cards} 当日没有技法卡
     */
    public Draft distill(LocalDate date, String styleId) {
        if (styleId == null || !styleId.matches("[a-z0-9][a-z0-9-]{1," + (MAX_STYLE_ID - 2) + "}")) {
            throw new HeronBusinessException(400, "trend_distill_id_invalid",
                "候选风格 id 只允许小写字母数字连字符：" + styleId);
        }
        Map<String, Map<String, Object>> cards = documents.summaryCards(date);
        if (cards.size() < MIN_CARDS) {
            throw new HeronBusinessException(409, "trend_distill_no_cards", "当日没有可蒸馏的技法卡：" + date);
        }
        String reply = model.chatWithoutUsage(prompt(date, cards, styleId), null);
        JsonNode plan = parse(reply);
        return write(styleId, plan, cards.size());
    }

    private static String prompt(LocalDate date, Map<String, ? extends Map<String, Object>> cards, String styleId) {
        return "把下面 " + cards.size() + " 条爆款技法卡蒸馏成一套候选视觉 Profile（风格 id：" + styleId
            + "）。技法卡来自 " + date + " 的学习拆解：\n" + JSON.writeValueAsString(cards) + """

            你是视觉语言设计师。技法卡是拆解结论，不是现成 Profile：先找到这批爆款的共同视觉语言
            （色彩倾向、字号层级、节奏、信息密度），再设计成一套自洽的新视觉语言——
            融合它们的共性，不是拼接每张卡的结论。产出三个文件的内容：

            tokens.css：完整令牌表（--bg/--fg/--muted/--body/--accent-glow 等语义色；
            --fs-title/--fs-title-sm/--fs-data/--fs-body/--fs-label 字号层级；--pad-frame 边距；
            环境层与动效时长令牌）。竖屏 1080×1920 画布：正文 ≥32px、标签 ≥24px。
            不定义 --accent（运行时注入）。只写 CSS，不写注释超过 120 字。
            profile.json：{"profileId":"%s","version":1,"bizLine":"(最匹配的内容线，从 news_explainer/data_board/trend_insight/knowledge_explainer 选)","visualMode":"2d","themeTone":"...","canvas":{"width":1080,"height":1920},"fonts":["Noto Sans SC"],"accentDefault":"#六位hex","motionDiscipline":{"maxStillSec":2.5,"endStableFrames":30,"maxFlashPerShot":1},"caption":"caption.css","skeletonRules":"skeleton-rules.json","creativeVoice":"这套视觉语言的定位，≤60字"}
            skeleton-rules.json：{"schemaVersion":"skeleton-rules/1","canvas":{"width":1080,"height":1920},"safePaddingPx":84,"subtitleBandY":{"minY":1280,"maxY":1660},"bottomBarReserveY":1660,"aigcBadge":{"required":true,"corner":"top-right","minFontPx":20},"minReadableFontPx":{"headline":56,"body":32,"label":24},"keepsMovingRequired":true,"forbidHardcodedHex":true,"forbidExternalUrls":true,"maxConsecutiveSameComposition":1}

            只输出一个 JSON 对象：{"tokensCss":"完整 CSS 文件内容","profileJson":{...},"skeletonRules":{...},"distillNote":"从技法卡吸收了什么，≤120字"}
            """.formatted(styleId);
    }

    private static JsonNode parse(String reply) {
        JsonNode node;
        try {
            node = JSON.readTree(ModelJson.unwrap(reply));
        } catch (RuntimeException e) {
            throw new HeronBusinessException(409, "trend_distill_invalid", "蒸馏输出不是 JSON");
        }
        String css = node.path("tokensCss").asString("");
        if (css.isBlank() || !css.contains(":root") || !css.contains("--fs-body")
            || !node.path("profileJson").isObject() || !node.path("skeletonRules").isObject()) {
            throw new HeronBusinessException(409, "trend_distill_invalid",
                "蒸馏缺 tokensCss/profileJson/skeletonRules 或令牌表不完整");
        }
        return node;
    }

    /** 写入候选目录：已存在即拒（人工改名入库后同名视为冲突），不做静默覆盖。 */
    private Draft write(String styleId, JsonNode plan, int cards) {
        Path draft = properties.getNewsProfile().toAbsolutePath().normalize().resolve(styleId + "-draft");
        if (Files.isDirectory(draft)) {
            throw new HeronBusinessException(409, "trend_distill_exists",
                "候选目录已存在（若已过审，请改名去 -draft 入库后重新蒸馏）：" + draft);
        }
        try {
            Files.createDirectories(draft);
            Files.writeString(draft.resolve("tokens.css"), plan.path("tokensCss").asString(), StandardCharsets.UTF_8);
            Files.writeString(draft.resolve("profile.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(plan.path("profileJson")), StandardCharsets.UTF_8);
            Files.writeString(draft.resolve("skeleton-rules.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(plan.path("skeletonRules")), StandardCharsets.UTF_8);
            Files.writeString(draft.resolve("sample.html"), sampleHtml(plan, styleId), StandardCharsets.UTF_8);
            Files.writeString(draft.resolve("DISTILL.md"), distillNote(plan, cards), StandardCharsets.UTF_8);
            log.info("风格蒸馏候选已写入 {}（{} 张技法卡）", draft, cards);
            return new Draft(draft, styleId, cards);
        } catch (IOException e) {
            throw new HeronBusinessException(503, "trend_distill_write_failed", "候选 Profile 无法写入", e);
        }
    }

    /** 样片 composition：用蒸馏令牌渲一帧可读的验证画面（标题 + 结论大字 + 母题位）。 */
    private static String sampleHtml(JsonNode plan, String styleId) {
        String note = plan.path("distillNote").asString("蒸馏验证样片");
        return """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8"><title>%s</title>
            <link rel="stylesheet" href="tokens.css"></head>
            <body><div data-composition-id="sample" data-width="1080" data-height="1920" data-duration="2.0"
              style="background:var(--bg);color:var(--fg);font-family:var(--font-body);padding:var(--pad-frame)">
              <h1 style="font-size:var(--fs-title);margin:480px 0 40px">%s</h1>
              <p style="font-size:var(--fs-body);max-width:80%%">%s</p>
              <div style="margin-top:120px;font-size:var(--fs-label);color:var(--fg)">蒸馏样片 · 待人工过审</div>
            </div></body></html>
            """.formatted(styleId, styleId, note);
    }

    /** 过审须知与蒸馏依据：审核者看这一个文件就能决定改名入库还是丢弃。 */
    private static String distillNote(JsonNode plan, int cards) {
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("status", "draft-未过审：ProfileRegistry 不认本目录，改名去掉 -draft 并在 select 登记后生效");
        head.put("cardsDistilled", cards);
        head.put("note", plan.path("distillNote").asString(""));
        return "---\n" + JSON.writeValueAsString(head) + "\n---\n\n"
            + "# 蒸馏候选\n\n"
            + "- 状态：**未过审**。本目录不进生产线（load 拒绝未登记目录）。\n"
            + "- 过审步骤：人工检查三个文件与样片 `sample.png` → 目录改名去掉 `-draft` → 在 `ProfileRegistry.select` 登记线内映射 → 提交。\n"
            + "- 样片：`sample.png`（如已生成）是用这套令牌渲的单镜验证画面，看风格是否真的成立。\n"
            + "- 蒸馏依据：`" + cards + "` 张当日技法卡，结论见 frontmatter。\n";
    }
}

package org.dromara.heron.service.impl.shared.research;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.domain.TrendTechniqueCard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 技法卡、storyboard 与当日横向汇总的唯一落盘边界。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendLearnDocuments {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> COMPARABLE_FIELDS = List.of("hookType", "cutRhythm", "textStyle", "infoDensity");

    private final HeronNewsProperties properties;
    private final ObjectProvider<Clock> clocks;

    /** 按容器里的 Clock 取当天日期；没有 Clock 时用系统默认时区。产物按这个日期分目录。 */
    public LocalDate today() {
        Clock clock = clocks.getIfAvailable();
        return LocalDate.now(clock == null ? Clock.systemDefaultZone() : clock);
    }

    /** 当日技法卡路径：{@code <输出目录>/<日期>/<awemeId>.md}；作品 ID 非法时抛 400。 */
    public Path cardPath(HeronTrendVideo video, LocalDate date) {
        return directory(date).resolve(safeId(video) + ".md");
    }

    /**
     * 把拆解原始产物（{@link TrendLearnStorage#RAW_FILES}）复制到当日 {@code _raw/<awemeId>/}，
     * 并把 storyboard.jpg 复制到当日目录；写入失败抛 503 {@code trend_learn_document_write_failed}。
     */
    public void saveRaw(HeronTrendVideo video, TrendVideoDecomposer.Decomposition decomposition, LocalDate date) {
        Path day = directory(date);
        Path raw = day.resolve("_raw").resolve(safeId(video));
        try {
            Files.createDirectories(raw);
            for (String name : TrendLearnStorage.RAW_FILES) {
                Files.copy(decomposition.outputDir().resolve(name), raw.resolve(name),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(decomposition.outputDir().resolve("storyboard.jpg"), day.resolve(safeId(video) + ".jpg"),
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new HeronBusinessException(503, "trend_learn_document_write_failed", "学习产物写入失败", e);
        }
    }

    /**
     * 写一张技法卡：frontmatter 汇总选样与原始产物元数据，正文是技法表、可复用点与模板落点。
     * 先写临时文件再原子替换，避免半份卡片被汇总读到；写入失败抛 503 {@code trend_learn_card_write_failed}。
     *
     * @param relevance 选样相关度（0–1），原样记入 frontmatter
     * @param reason    选样理由
     * @param ossId     原片 OSS ID，可为空
     * @param rawKeys   原始产物名 → OSS 键，可为空 Map
     * @return 最终写入的卡片路径
     */
    public Path saveCard(HeronTrendVideo video, TrendTechniqueCard card, double relevance, String reason,
                         LocalDate date, Long ossId, Map<String, String> rawKeys) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("awemeId", video.getAwemeId());
        meta.put("platform", video.getPlatform());
        meta.put("description", video.getDescription());
        meta.put("author", video.getAuthorNickname());
        meta.put("durationMs", video.getDurationMs());
        meta.put("relevance", relevance);
        meta.put("selectionReason", reason);
        meta.put("ossId", ossId);
        meta.put("rawOssKeys", rawKeys);
        meta.putAll(card.toMap());
        String body = "---\n" + JSON.writeValueAsString(meta) + "\n---\n\n"
            + "# 技法卡 · " + safeId(video) + "\n\n"
            + "- 来源：" + text(video.getPlatform()) + " / " + text(video.getAuthorNickname()) + "\n"
            + "- 时长：" + video.getDurationMs() + " ms\n"
            + "- 选样相关度：" + relevance + "\n"
            + "- 选样理由：" + text(reason) + "\n"
            + "- 原片 OSS ID：" + ossId + "\n\n"
            + "![storyboard](" + safeId(video) + ".jpg)\n\n"
            + "## 核心技法\n\n"
            + "| 字段 | 结论 |\n| --- | --- |\n"
            + "| hookType | " + cell(card.hookType()) + " |\n"
            + "| firstShotSeconds | " + card.firstShotSeconds() + " |\n"
            + "| avgShotSeconds | " + card.avgShotSeconds() + " |\n"
            + "| cutRhythm | " + cell(card.cutRhythm()) + " |\n"
            + "| textStyle | " + cell(card.textStyle()) + " |\n"
            + "| motionTypes | " + cell(String.join(" / ", card.motionTypes())) + " |\n"
            + "| infoDensity | " + cell(card.infoDensity()) + " |\n\n"
            + "## 可复用点\n\n" + bullets(card.reusablePoints()) + "\n"
            + "## Heron 模板落点\n\n" + text(card.heronTemplateFit()) + "\n\n"
            + "## 原始产物\n\n" + bullets(TrendLearnStorage.RAW_FILES.stream()
                .map(name -> "[" + name + "](_raw/" + safeId(video) + "/" + name + ")").toList()) + "\n";
        Path target = cardPath(video, date);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temporary, body, java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new HeronBusinessException(503, "trend_learn_card_write_failed", "技法卡写入失败", e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                log.warn("技法卡临时文件清理失败 {}", temporary, e);
            }
        }
    }

    /**
     * 写当日横向汇总到 {@code techniques.md}：从当日全部卡片里只挑 {@code learnedAwemeIds}
     * （成功学习的），比较共同点、逐条对比与差异速记。目录或文件写失败抛 503。
     *
     * @param learnedAwemeIds 只纳入这些作品的卡片；传空集合会写一份「今日无样本」的空汇总
     * @return 汇总文件路径
     */
    public Path writeSummary(LocalDate date, Set<String> learnedAwemeIds) {
        Path day = directory(date);
        try {
            Files.createDirectories(day);
        } catch (IOException e) {
            throw new HeronBusinessException(503, "trend_learn_summary_dir_failed", "学习汇总目录无法创建", e);
        }
        List<Map<String, Object>> cards = summaryCards(date).entrySet().stream()
            .filter(entry -> learnedAwemeIds.contains(entry.getKey()))
            .map(Map.Entry::getValue).collect(Collectors.toList());
        StringBuilder text = new StringBuilder("# 爆款技法横向汇总 · " + date + "\n\n");
        text.append("成功学习 ").append(cards.size()).append(" 条。\n\n");
        if (cards.isEmpty()) {
            text.append("本日暂无成功学习样本。\n");
        } else {
            text.append("## 共同点\n\n");
            boolean common = false;
            for (String field : COMPARABLE_FIELDS) {
                Set<String> values = cards.stream().map(card -> text(card.get(field))).collect(Collectors.toSet());
                if (cards.size() > 1 && values.size() == 1) {
                    text.append("- `").append(field).append("`：").append(values.iterator().next()).append('\n');
                    common = true;
                }
            }
            Set<String> reusable = commonValues(cards, "reusablePoints");
            for (String point : reusable) {
                text.append("- 可复用：").append(point).append('\n');
                common = true;
            }
            if (!common) {
                text.append("- 本批没有完全一致的字段；差异见下表。\n");
            }
            text.append("\n## 逐条对比\n\n")
                .append("| awemeId | hookType | 首镜秒 | 平均镜头秒 | 切节奏 | 文字风格 | 信息密度 | 模板落点 |\n")
                .append("| --- | --- | ---: | ---: | --- | --- | --- | --- |\n");
            for (Map<String, Object> card : cards) {
                text.append("| ").append(cell(text(card.get("awemeId")))).append(" | ")
                    .append(cell(text(card.get("hookType")))).append(" | ")
                    .append(card.get("firstShotSeconds")).append(" | ")
                    .append(card.get("avgShotSeconds")).append(" | ")
                    .append(cell(text(card.get("cutRhythm")))).append(" | ")
                    .append(cell(text(card.get("textStyle")))).append(" | ")
                    .append(cell(text(card.get("infoDensity")))).append(" | ")
                    .append(cell(text(card.get("heronTemplateFit")))).append(" |\n");
            }
            text.append("\n## 差异速记\n\n");
            for (Map<String, Object> card : cards) {
                text.append("- **").append(text(card.get("awemeId"))).append("**：")
                    .append(text(card.get("hookType"))).append("；")
                    .append(text(card.get("cutRhythm"))).append("；")
                    .append(text(card.get("heronTemplateFit"))).append('\n');
            }
        }
        Path target = day.resolve("techniques.md");
        try {
            Files.createDirectories(day);
            Files.writeString(target, text, java.nio.charset.StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new HeronBusinessException(503, "trend_learn_summary_write_failed", "学习横向汇总写入失败", e);
        }
    }

    /**
     * 当日全部技法卡的 frontmatter（awemeId → meta）。
     * 损坏文件跳过不阻断——汇总与蒸馏都要这个容错：一张坏卡不该挡住整批结论。
     */
    public Map<String, Map<String, Object>> summaryCards(LocalDate date) {
        Path day = directory(date);
        Map<String, Map<String, Object>> cards = new LinkedHashMap<>();
        if (!Files.isDirectory(day)) {
            return cards;
        }
        try (Stream<Path> files = Files.list(day)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".md"))
                .filter(path -> !"techniques.md".equals(path.getFileName().toString()))
                .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                try {
                    Map<String, Object> card = JSON.readValue(frontMatter(Files.readString(file,
                        java.nio.charset.StandardCharsets.UTF_8)), Map.class);
                    if (text(card.get("awemeId")) != null) {
                        cards.put(text(card.get("awemeId")), card);
                    }
                } catch (RuntimeException | IOException e) {
                    log.warn("技法卡汇总跳过损坏文件 {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new HeronBusinessException(503, "trend_learn_summary_read_failed", "学习汇总目录无法读取", e);
        }
        return cards;
    }

    private static Set<String> commonValues(List<Map<String, Object>> cards, String field) {
        if (cards.size() < 2) {
            return Set.of();
        }
        Set<String> common = new LinkedHashSet<>(stringList(cards.get(0).get(field)));
        for (Map<String, Object> card : cards) {
            common.retainAll(stringList(card.get(field)));
        }
        return common;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return value instanceof List<?> list
            ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static String frontMatter(String markdown) {
        if (!markdown.startsWith("---\n")) {
            throw new IllegalArgumentException("技法卡缺少 front matter");
        }
        int end = markdown.indexOf("\n---", 4);
        if (end < 0) {
            throw new IllegalArgumentException("技法卡 front matter 未闭合");
        }
        return markdown.substring(4, end);
    }

    private Path directory(LocalDate date) {
        return properties.getTrendLearnOutputDir().toAbsolutePath().normalize().resolve(date.toString());
    }

    private static String safeId(HeronTrendVideo video) {
        String id = video.getAwemeId();
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new HeronBusinessException(400, "trend_learn_id_invalid", "学习产物路径含非法作品 ID");
        }
        return id;
    }

    private static String bullets(List<String> items) {
        return items.stream().map(item -> "- " + item).collect(Collectors.joining("\n"));
    }

    private static String cell(String value) {
        return text(value).replace("|", "\\|").replace("\n", " ");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.service.impl.shared.production.ProfileRegistry;
import org.dromara.heron.service.impl.shared.research.TrendDistillService;
import org.dromara.heron.service.impl.shared.research.TrendLearnDocuments;
import org.dromara.heron.service.impl.shared.support.OpenAiModelClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风格蒸馏闭环契约：解析只认完整令牌表、候选写进 -draft 目录（未过审不进生产线）、
 * 同名候选即拒（人工改名入库后不容静默覆盖）、风格 id 闭防路径注入。
 */
@DisplayName("风格蒸馏：技法卡 → 候选 Profile")
class TrendDistillServiceTest {

    @TempDir
    Path work;

    private TrendDistillService service() {
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setNewsProfile(work.resolve("profiles"));
        return new TrendDistillService(new DocumentsStub(), new OpenAiModelClientStub(), properties);
    }

    @Test
    @DisplayName("风格 id 只允许小写字母数字连字符，非法即拒")
    void styleIdVocabularyIsClosed() {
        TrendDistillService service = service();
        assertThrows(HeronBusinessException.class, () -> service.distill(LocalDate.now(), "Bad_Id"));
        assertThrows(HeronBusinessException.class, () -> service.distill(LocalDate.now(), "../escape"));
        assertThrows(HeronBusinessException.class, () -> service.distill(LocalDate.now(), "-x"));
    }

    @Test
    @DisplayName("蒸馏产物写进 -draft 目录，含三契约文件与过审须知")
    void writesDraftWithContracts() {
        Path profiles = work.resolve("profiles");
        TrendDistillService.Draft draft = service().distill(LocalDate.of(2026, 11, 27), "neon-pulse");
        assertEquals("neon-pulse", draft.styleId());
        assertEquals(1, draft.cards());
        assertTrue(draft.directory().getFileName().toString().endsWith("-draft"), "候选必须带 -draft 后缀");
        assertTrue(Files.isRegularFile(draft.directory().resolve("tokens.css")));
        assertTrue(Files.isRegularFile(draft.directory().resolve("profile.json")));
        assertTrue(Files.isRegularFile(draft.directory().resolve("skeleton-rules.json")));
        String note = read(draft.directory().resolve("DISTILL.md"));
        assertTrue(note.contains("未过审"));
        // 同名候选已存在：人工改名入库前，蒸馏不容静默覆盖
        assertThrows(HeronBusinessException.class, () -> service().distill(LocalDate.of(2026, 11, 27), "neon-pulse"));
        // -draft 目录不被 ProfileRegistry 认：未过审资产不进生产线
        assertThrows(HeronBusinessException.class,
            () -> new ProfileRegistry(profileProperties()).load(
                org.dromara.heron.domain.ProductionBizType.news_explainer, "neon-pulse-draft",
                ProfileRegistry.DEFAULT_VOICE));
    }

    @Test
    @DisplayName("蒸馏输出缺令牌表或契约即拒")
    void incompleteDistillRejected() {
        // 模型桩对含 broken 的提示返回不完整输出：解析在写盘之前，失败不落半份产物
        TrendDistillService service = service();
        assertThrows(HeronBusinessException.class,
            () -> service.distill(LocalDate.of(2026, 11, 27), "broken"));
        assertEquals(false, Files.exists(work.resolve("profiles/broken-draft")));
    }

    private HeronProductionProperties profileProperties() {
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setNewsProfile(Path.of("../..").toAbsolutePath().normalize().resolve("data/profiles"));
        return properties;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 只实现 summaryCards：蒸馏测试不碰真实学习目录。 */
    private static final class DocumentsStub extends TrendLearnDocuments {
        DocumentsStub() {
            super(new HeronNewsProperties(), new org.springframework.beans.factory.ObjectProvider<>() {
                @Override
                public java.time.Clock getObject(Object... args) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public java.time.Clock getIfAvailable() {
                    return null;
                }

                @Override
                public java.time.Clock getIfUnique() {
                    return null;
                }
            });
        }

        @Override
        public Map<String, Map<String, Object>> summaryCards(LocalDate date) {
            if (!LocalDate.of(2026, 11, 27).equals(date)) {
                return Map.of();
            }
            return Map.of("aweme-1", Map.of(
                "awemeId", "aweme-1", "hookType", "数字反差", "cutRhythm", "快切",
                "textStyle", "大字高对比", "infoDensity", "高"));
        }
    }

    /** 固定蒸馏输出的模型桩：绕开真实 HTTP，只验蒸馏编排。 */
    private static final class OpenAiModelClientStub extends OpenAiModelClient {
        OpenAiModelClientStub() {
            super(null, null, null);
        }

        @Override
        public String chatWithoutUsage(String systemPrompt, String userPrompt) {
            String prompt = systemPrompt == null ? "" : systemPrompt;
            if (prompt.contains("broken")) {
                return "{\"tokensCss\":\":root{}\",\"profileJson\":{},\"skeletonRules\":{}}";
            }
            return """
                {"tokensCss":":root{--bg:#000;--fg:#fff;--fs-body:38px}","profileJson":{"profileId":"neon-pulse"},"skeletonRules":{"canvas":{"width":1080,"height":1920}},"distillNote":"吸收数字反差与大字高对比"}
                """;
        }

        @Override
        public String modelName() {
            return "stub";
        }
    }
}

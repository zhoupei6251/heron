package org.dromara.heron.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.NewsVideoVersionVo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionReceiptVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.service.impl.EditorialDecisionService;
import org.dromara.heron.support.NewsMysqlTestConfiguration;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 知识解释线的自动发布闭环：自动认领 → 知识 kind 冻结 → 七类产物 → 自动审稿放行 → sandbox 出口。
 *
 * <p>这里不跑模型和渲染：真模型与真渲染由 {@code KnowledgeRealChainTest} 覆盖，
 * 本用例验的是自动模式与发布出口对知识线没有另一套规则。
 */
@Tag("mysql")
@ContextConfiguration(classes = NewsMysqlTestConfiguration.class, inheritLocations = false)
class KnowledgeSandboxPublishMysqlTest extends NewsMysqlTestSupport {

    private static final String SOURCE_KEY = "how-to-live-better";
    private static final String TEMPLATE = "knowledge-mono-9x16";
    private static final String ATTRIBUTION = "内容改编自《高性价比人生指南》（https://github.com/eternity4719/HowToLiveBetter），CC BY 4.0，有修改。";

    @Autowired private HeronNewsCandidateMapper candidateMapper;

    @Test
    @DisplayName("知识视频自动确认后走 sandbox 出口，版本与候选都留下发布事实")
    void autoConfirmPublishesToSandbox() throws Exception {
        allowProduction();
        allowKnowledgeTemplate();
        long candidateId = knowledgeCandidate();
        EditorialSelection selection = new EditorialSelection(EditorialSelection.KNOWLEDGE_EXPLAINER, candidateId,
            SOURCE_KEY, false, TEMPLATE, "第一次租房先做这三件事", List.of("15-01", "15-03", "05-03"));
        EditorialDecisionService.Decision decision = new EditorialDecisionService.Decision("PUBLISH",
            EditorialSelection.KNOWLEDGE_EXPLAINER, "知识线备用槽位", "按合同、付款、补贴的顺序讲", candidateId);

        var work = maintenance.claimForAuto(selection, OWNER);
        ProductionReceiptVo receipt = maintenance.generateForAuto(work.getWorkId(),
            "auto:knowledge:" + UUID.randomUUID(), decision, selection);
        ProductionRunVo run = execution.claim(ProductionBizType.news_video).orElseThrow();
        execution.succeed(run, new ProductionResultVo(knowledgeFiles(run), knowledgeResult(run)));

        NewsVideoVersionVo published = maintenance.confirmForAuto(work.getWorkId(), receipt.versionId(), OWNER);

        assertEquals("sandbox", published.getPublishSource());
        assertNotNull(published.getPublishAt());
        assertEquals("sandbox", published.getPublishAttempt().getSource());
        assertEquals("published", published.getPublishAttempt().getStatus());
        assertNotNull(jdbc.queryForObject(
            "SELECT published_at FROM heron_news_candidate WHERE candidate_id=?", LocalDateTime.class, candidateId));
        assertEquals("0", jdbc.queryForObject(
            "SELECT auto_publish FROM heron_news_work WHERE work_id=?", String.class, work.getWorkId()));
    }

    private long knowledgeCandidate() {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setCandidateId(IdUtil.getSnowflakeNextId());
        candidate.setTitle("第一次租房先做这三件事");
        candidate.setSummary("押金条款写清楚，比什么都省钱");
        candidate.setNormalizedTitle("第一次租房先做这三件事");
        candidate.setDedupKey(DigestUtil.sha256Hex("knowledge:" + candidate.getCandidateId()));
        candidate.setSourceKind(HeronNewsCandidate.SOURCE_KNOWLEDGE);
        candidate.setSourceKey(SOURCE_KEY);
        candidate.setEvidence(JsonUtils.toJsonString(Map.of("items", List.of(Map.of(
            "sourceItemId", "15-01", "sourceUrl", "https://example.test/15-01", "topicOnly", false)))));
        candidate.setHitCount(1L);
        candidate.setHeatScore(1L);
        candidate.setFirstSeenAt(LocalDateTime.now());
        candidate.setLastSeenAt(LocalDateTime.now());
        candidate.setCreateBy(OWNER);
        candidate.setUpdateBy(OWNER);
        candidate.setCreateTime(LocalDateTime.now());
        candidate.setUpdateTime(LocalDateTime.now());
        assertEquals(1, candidateMapper.insert(candidate));
        return candidate.getCandidateId();
    }

    /** 认领会校验 auto 模板存在，所以先在测试模板根下放一套 knowledge 模板的 LOCK。 */
    private void allowKnowledgeTemplate() throws Exception {
        Path template = Files.createDirectories(properties.getNewsTemplate().resolve(TEMPLATE));
        Files.writeString(template.resolve("LOCK.json"), JsonUtils.toJsonString(Map.of(
            "templateKey", TEMPLATE, "templateVersion", "fixture-1",
            "fontSha256", "a".repeat(64), "voiceKey", "default", "voiceVersion", "fixture-1",
            "assetManifestSha256", "b".repeat(64))));
    }

    private static String knowledgeScript() {
        return """
            {"title":"第一次租房先做这三件事","closing":"先合同、再付款、后补贴。%s",\
            "source_name":"高性价比人生指南",\
            "source_url":"https://github.com/eternity4719/HowToLiveBetter","fetched_at":"2026-11-20",\
            "body_sha256":"%s",\
            "scenes":[{"lead":"押金条款先写清","body":"押金数额、退还时间和能扣的情形，签字前写进合同。","duration_s":15,\
            "entry_ids":["15-01"]},\
            {"lead":"租金押金别转给中介","body":"房租和押金直接付给房东，付款前核对收款人。","duration_s":15,\
            "entry_ids":["15-03"]},\
            {"lead":"租房也能提公积金","body":"租房属于可提取情形，受理后三个工作日答复。","duration_s":15,\
            "entry_ids":["05-03"]}]}""".formatted(ATTRIBUTION, "c".repeat(64));
    }

    private static String knowledgeChecks() {
        return JsonUtils.toJsonString(java.util.stream.Stream.of("schema", "evidence", "citation", "audio",
                "audio_mix", "subtitles", "render", "decode", "visual", "aigc_metadata", "aigc_visible", "assets",
                "scene_type", "storyboard", "video_signal")
            .map(gate -> Map.of("gate", gate, "inputLocator", "fixture", "passed", true,
                "reason", "仅事务测试，非真实生产验收", "measured", Map.of()))
            .toList());
    }

    private List<ProductionArtifactVo> knowledgeFiles(ProductionRunVo run) throws Exception {
        Path directory = properties.getDataRoot().resolve("videos/" + run.bizId() + "/v" + run.versionId()
            + "/a" + run.attemptNo() + "-" + run.runToken());
        Files.createDirectories(directory);
        List<ProductionArtifactVo> files = new ArrayList<>();
        for (String kind : List.of("mp4", "contact_sheet", "script", "subtitles", "checks", "asset_manifest",
            "citations")) {
            String id = UUID.randomUUID().toString();
            String ext = switch (kind) {
                case "mp4" -> "mp4";
                case "contact_sheet" -> "png";
                case "subtitles" -> "srt";
                default -> "json";
            };
            Path file = directory.resolve(id + "." + ext);
            switch (kind) {
                case "mp4" -> Files.write(file, mp4Fixture());
                case "contact_sheet" -> writePng(file, run.versionId());
                case "script" -> Files.writeString(file, knowledgeScript());
                case "checks" -> Files.writeString(file, knowledgeChecks());
                case "subtitles" -> Files.writeString(file, "1\n00:00:00,000 --> 00:00:01,000\n测试\n");
                default -> Files.writeString(file, "{}");
            }
            files.add(new ProductionArtifactVo(id, kind,
                properties.getDataRoot().relativize(file).toString().replace('\\', '/'),
                Files.size(file), DigestUtil.sha256Hex(file.toFile()),
                switch (kind) {
                    case "mp4" -> "video/mp4";
                    case "contact_sheet" -> "image/png";
                    case "subtitles" -> "application/x-subrip";
                    default -> "application/json";
                }));
        }
        return files;
    }

    private static byte[] mp4Fixture() {
        // 1 秒黑色 mpeg4：事务测试只需要一个可校验的真实 mp4，不从生产流水线来
        try {
            Path dir = Files.createTempDirectory("knowledge_publish_mp4_");
            Path out = dir.resolve("fixture.mp4");
            Process process = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi",
                "-i", "color=c=black:s=16x16:r=1", "-t", "1", "-c:v", "mpeg4", "-y", out.toString())
                .redirectErrorStream(true).start();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("测试需要 PATH 中的 ffmpeg");
            }
            return Files.readAllBytes(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writePng(Path file, long versionId) throws Exception {
        java.awt.image.BufferedImage image =
            new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int column = 0; column < 16; column++) {
            image.setRGB(column, 0, (int) ((versionId >>> (4 * column)) & 0xFF) * 0x010101);
        }
        javax.imageio.ImageIO.write(image, "png", file.toFile());
    }

    private static String knowledgeResult(ProductionRunVo run) {
        String review = JsonUtils.toJsonString(Map.of(
            "schemaVersion", "1.0", "result", "PASS", "issues", List.of(), "repairAttempts", 0,
            "promptVersion", "knowledge-review", "reviewedAt", "2026-11-20T00:00:00"));
        return JsonUtils.toJsonString(Map.of(
            "script", JsonUtils.parseObject(knowledgeScript(), Object.class),
            "checks", JsonUtils.parseObject(knowledgeChecks(), Object.class),
            "editorialReview", JsonUtils.parseObject(review, Object.class),
            "durationMs", 45_000));
    }
}

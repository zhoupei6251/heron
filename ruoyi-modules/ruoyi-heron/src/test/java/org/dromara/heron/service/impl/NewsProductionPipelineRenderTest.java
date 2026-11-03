package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.NewsScriptAuthor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真工具跑一遍渲染半程（假稿件 → edge-tts → hyperframes check/render → ffprobe → AIGC 元数据 → 联络表 → 六类产物）。
 * 单独打 tag：它要几分钟真渲染，默认构建不跑，也不依赖数据库与模型。
 */
@Tag("render")
@DisplayName("新闻流水线真渲染：十道闸与六类产物全部来自真实命令")
class NewsProductionPipelineRenderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCRIPT = """
        {"source_url":"https://example.com/tech/release","fetched_at":"%s",
         "body_sha256":"%s",
         "title":"新款折叠屏手机上市",
         "scenes":[
           {"lead":"铰链寿命提到四十万次","body":"厂商在今日的发布会上公布新一代折叠机型，主打铰链与整机耐用度。官方资料显示，铰链开合寿命较上一代提升约一倍，屏幕折痕在侧视角度下明显变浅。","duration_s":12},
           {"lead":"起售价与上一代持平","body":"价格方面，起步版本不做上调，存储升级需要加价。渠道反馈首销期以旧换新补贴是主要促销手段，线下门店同步开启真机体验。","duration_s":12},
           {"lead":"下周开启全渠道现货","body":"官方宣布下周一开启全渠道现货发售，首批覆盖国内主要城市，海外上市时间表尚未公布。行业机构预计本季度折叠屏整体出货量小幅回升。","duration_s":12}],
         "closing":"我们会继续跟进真机实测与售后条款变化。",
         "source_name":"公开报道整理"}
        """.formatted(OffsetDateTime.now(ZoneOffset.UTC).toString(),
            DigestUtil.sha256Hex("foldable-phone-article"));

    @Test
    @DisplayName("六类产物齐备，十道闸全部由真实命令判定通过")
    void rendersOneRealVideo() {
        Path repository = Path.of("..", "..").toAbsolutePath().normalize();
        Path template = repository.resolve("data").resolve("templates").resolve("news-9x16");
        Path cli = repository.resolve("data").resolve("tools").resolve("node_modules")
            .resolve("hyperframes").resolve("bin").resolve("hyperframes.mjs");
        assertTrue(Files.isDirectory(template), "缺少模板目录：" + template);
        assertTrue(Files.isRegularFile(cli), "缺少 hyperframes 入口：" + cli);

        // 真渲染会让 Chrome 短暂持有目录句柄，@TempDir 在 Windows 上删不掉；落进被忽略的 data/it-render 还留下可看的成片。
        Path dataRoot = repository.resolve("data").resolve("it-render").resolve(UUID.randomUUID().toString());
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(dataRoot);
        properties.setNewsTemplate(template);
        properties.setHyperframes(cli.toString());
        properties.setGuardEnabled(false);
        NewsProductionPipeline pipeline = new NewsProductionPipeline(
            fixedAuthor(SCRIPT), new ProductionProcessService(properties,
            new ProductionArtifactService(properties)), new ProductionArtifactService(properties), properties);

        ProductionRunVo run = new ProductionRunVo(7001L, ProductionBizType.news_video, 10L, 5L, 1,
            UUID.randomUUID().toString(), lockedAssets(template), null);
        ProductionResultVo result = pipeline.execute(run);

        JsonNode content = JSON.readTree(result.resultJson());
        assertEquals(content.path("script").toString(), JSON.readTree(SCRIPT).toString(), "入库稿件必须等于写稿结果");
        assertTrue(content.path("durationMs").asLong() >= 30_000 && content.path("durationMs").asLong() <= 90_000,
            "实测时长应落在契约区间：" + content.path("durationMs").asLong());
        List<String> kinds = new ArrayList<>();
        for (ProductionArtifactVo artifact : result.artifacts()) {
            kinds.add(artifact.kind());
            assertTrue(artifact.bytes() > 0, artifact.kind() + " 是空产物");
        }
        assertEquals(List.of("asset_manifest", "checks", "contact_sheet", "mp4", "script", "subtitles"),
            kinds.stream().sorted().toList());

        JsonNode checks = content.path("checks");
        assertEquals(10, checks.size(), checks.toString());
        for (JsonNode gate : checks) {
            assertTrue(gate.path("passed").asBoolean(), gate.path("gate").asString("") + " 未通过");
            assertTrue(gate.path("measured").size() > 0, gate.path("gate").asString("") + " 没有实测值");
        }
        JsonNode visual = find(checks, "visual");
        assertEquals(0, visual.path("measured").path("blankFrames").asInt(), "联络表出现空白帧");
        assertTrue(visual.path("measured").path("checkErrors").asInt() == 0, "版式闸有 error");
        assertTrue(find(checks, "audio").path("measured").path("voiceBytes").asLong() > 1000);
        assertTrue(find(checks, "assets").path("measured").path("manifestMatched").asBoolean());
    }

    private static JsonNode find(JsonNode checks, String gate) {
        for (JsonNode item : checks) {
            if (gate.equals(item.path("gate").asString(""))) {
                return item;
            }
        }
        throw new AssertionError("缺少闸 " + gate);
    }

    /** 认领时锁定的资产快照：模板目录名与实算哈希，与部署侧要写的值同一算法。 */
    private static String lockedAssets(Path template) {
        Map<String, Object> assets = new TreeMap<>();
        assets.put("templateKey", template.getFileName().toString());
        assets.put("templateVersion", "1");
        assets.put("fontSha256", DigestUtil.sha256Hex(
            template.resolve("fonts").resolve("NotoSansSC-Regular.otf").toFile()));
        assets.put("voiceKey", "zh-CN-XiaoxiaoNeural");
        assets.put("voiceVersion", "1");
        assets.put("assetManifestSha256", DigestUtil.sha256Hex(
            template.resolve("assets-manifest.json").toFile()));
        return JSON.writeValueAsString(Map.of("assets", assets));
    }

    private static NewsScriptAuthor fixedAuthor(String script) {
        return run -> script;
    }
}

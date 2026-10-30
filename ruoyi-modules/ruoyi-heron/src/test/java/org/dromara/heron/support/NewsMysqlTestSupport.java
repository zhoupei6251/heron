package org.dromara.heron.support;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.*;
import org.dromara.heron.domain.vo.*;
import org.dromara.heron.service.*;
import org.dromara.heron.service.impl.NewsWorkServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ContextConfiguration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 所有 DDL/DML 仅由父类已校验的 heron_it_* DataSource 执行。 */
@ContextConfiguration(classes = NewsMysqlTestConfiguration.class, inheritLocations = false)
public abstract class NewsMysqlTestSupport extends HeronMysqlTestSupport {
    protected static final long OWNER = 101L;
    protected static final long OTHER = 102L;
    @Autowired protected INewsWorkService news;
    @Autowired protected NewsWorkServiceImpl maintenance;
    @Autowired protected IProductionExecutionService execution;
    @Autowired protected HeronProductionProperties properties;
    @Autowired protected MutableClock clock;
    @Autowired protected ConfigurableApplicationContext context;
    private static byte[] testMp4;

    @BeforeEach
    void newsSchema() throws Exception {
        resetSchema();
        clearProductionData();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String table : List.of("heron_video_version", "heron_news_work", "heron_news_candidate", "heron_news_source")) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
            Path ddl = Path.of("../../script/sql/heron/002-news.sql");
            if (!Files.isRegularFile(ddl)) ddl = Path.of("script/sql/heron/002-news.sql");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(ddl));
        }
        var factory = (DefaultListableBeanFactory) context.getBeanFactory();
        factory.destroySingleton("newsProductionPipeline");
        properties.setDataRoot(Files.createTempDirectory("heron_it_news_"));
        properties.setWorkerEnabled(true);
        // Capability checks only; no executable is invoked by the production services in these tests.
        String java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        properties.setNode(java);
        properties.setFfmpeg(java);
        properties.setFfprobe(java);
        grantNews(OWNER);
        grantNews(OTHER);
        loginAs(OWNER);
    }

    protected void grantNews(long actor) {
        grantPermissions(actor, "heron:newsCandidate:manual", "heron:newsCandidate:claim",
            "heron:newsCandidate:list", "heron:newsCandidate:query", "heron:newsWork:list",
            "heron:newsWork:query", "heron:newsWork:edit", "heron:newsWork:generate",
            "heron:newsWork:review", "heron:newsWork:reviewPreview", "heron:newsWork:download");
    }

    protected void allowProduction() {
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("news-fixture-assets",
            Map.of("heron.news.production-assets", JsonUtils.toJsonString(Map.of("templateKey", "default", "templateVersion", "fixture-1",
                "fontSha256", "a".repeat(64), "voiceKey", "default", "voiceVersion", "fixture-1", "assetManifestSha256", "b".repeat(64))))));
        if (!context.containsBean("newsProductionPipeline")) {
            context.getBeanFactory().registerSingleton("newsProductionPipeline", (IProductionPipeline) run -> {
                throw new AssertionError("事务 fixture 不执行模型或流水线");
            });
        }
    }

    protected NewsCandidateBo manual(String title, String summary) {
        NewsCandidateBo bo = new NewsCandidateBo();
        bo.setTitle(title);
        bo.setSummary(summary);
        return bo;
    }

    protected NewsWorkVo work() {
        return news.claim(news.addManual(manual("测试新闻" + UUID.randomUUID(), "来源事实与授权待人工核对")).getCandidateId());
    }

    protected Completed completed() throws Exception { return completed(work()); }

    protected Completed completed(NewsWorkVo work) throws Exception {
        allowProduction();
        ProductionReceiptVo receipt = news.generate(work.getWorkId(), new NewsGenerateBo(UUID.randomUUID().toString(), work.getRevision()));
        ProductionRunVo run = execution.claim(ProductionBizType.news_video).orElseThrow();
        List<ProductionArtifactVo> files = fixtureFiles(run);
        String checks = checksJson();
        String result = JsonUtils.toJsonString(Map.of("script", JsonUtils.parseObject(scriptJson(), Object.class),
            "checks", JsonUtils.parseObject(checks, Object.class), "durationMs", 1000));
        org.junit.jupiter.api.Assertions.assertTrue(execution.succeed(run, new ProductionResultVo(files, result)));
        return new Completed(work.getWorkId(), receipt.versionId(), files);
    }

    protected List<ProductionArtifactVo> fixtureFiles(ProductionRunVo run) throws Exception {
        Path directory = properties.getDataRoot().resolve("videos/" + run.bizId() + "/v" + run.versionId()
            + "/a" + run.attemptNo() + "-" + run.runToken());
        Files.createDirectories(directory);
        List<ProductionArtifactVo> files = new ArrayList<>();
        for (String kind : List.of("mp4", "contact_sheet", "script", "subtitles", "checks", "asset_manifest")) {
            String id = UUID.randomUUID().toString();
            String ext = kind.equals("mp4") ? "mp4" : kind.equals("contact_sheet") ? "png" : kind.equals("subtitles") ? "srt" : "json";
            Path file = directory.resolve(id + "." + ext);
            if (kind.equals("mp4")) Files.write(file, realTestMp4());
            else if (kind.equals("contact_sheet")) {
                BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, (int) run.versionId());
                ImageIO.write(image, "png", file.toFile());
            } else Files.writeString(file, kind.equals("checks") ? checksJson() : kind.equals("script") ? scriptJson()
                : kind.equals("subtitles") ? "1\n00:00:00,000 --> 00:00:01,000\n测试\n" : "{}");
            files.add(new ProductionArtifactVo(id, kind, properties.getDataRoot().relativize(file).toString().replace('\\', '/'),
                Files.size(file), DigestUtil.sha256Hex(file.toFile()), kind.equals("mp4") ? "video/mp4"
                : kind.equals("contact_sheet") ? "image/png" : kind.equals("subtitles") ? "application/x-subrip" : "application/json"));
        }
        return files;
    }

    protected static String scriptJson() {
        return JsonUtils.toJsonString(Map.of("title", "测试新闻", "scenes", List.of()));
    }

    protected static String checksJson() {
        return JsonUtils.toJsonString(List.of("schema", "evidence", "audio", "subtitles", "render", "decode",
                "visual", "aigc_metadata", "aigc_visible", "assets").stream()
            .map(gate -> Map.of("gate", gate, "inputLocator", "fixture", "passed", true,
                "reason", "仅事务测试，非真实生产验收", "measured", Map.of())).toList());
    }

    private static synchronized byte[] realTestMp4() throws Exception {
        if (testMp4 == null) {
            Path directory = Files.createTempDirectory("heron_it_news_media_");
            Path output = directory.resolve("fixture.mp4");
            Path log = directory.resolve("ffmpeg.log");
            // A real, locally generated MP4 fixture, never advertised as news pipeline output.
            Process process = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i",
                "color=c=black:s=16x16:r=1", "-t", "1", "-c:v", "mpeg4", "-y", output.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("测试 MP4 fixture 生成超时");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("测试需要 PATH 中的 ffmpeg");
            testMp4 = Files.readAllBytes(output);
        }
        return testMp4.clone();
    }

    protected record Completed(long workId, long versionId, List<ProductionArtifactVo> files) {
        public ProductionArtifactVo file(String kind) { return files.stream().filter(f -> f.kind().equals(kind)).findFirst().orElseThrow(); }
        public NewsReviewBo approve() { return new NewsReviewBo("approved", file("contact_sheet").sha256(), file("mp4").sha256(), null); }
    }
}

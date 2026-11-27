package org.dromara.heron.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.linpeilie.mapstruct.MapstructAutoConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.mybatis.core.mapper.LambdaCrudChainWrapper;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.HeronNewsPublishAttempt;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.domain.HeronProductionLane;
import org.dromara.heron.domain.HeronProductionTask;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.HeronVideoVersionToNewsVideoVersionVoMapperImpl;
import org.dromara.heron.domain.PublishReceipt;
import org.dromara.heron.domain.PublishRequest;
import org.dromara.heron.domain.bo.NewsPublishBo;
import org.dromara.heron.domain.bo.NewsPublishResolveBo;
import org.dromara.heron.domain.bo.NewsReviewBo;
import org.dromara.heron.domain.vo.NewsVideoVersionVo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.mapper.HeronNewsPublishAttemptMapper;
import org.dromara.heron.mapper.HeronNewsWorkMapper;
import org.dromara.heron.mapper.HeronProductionLaneMapper;
import org.dromara.heron.mapper.HeronProductionTaskMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.IPublishAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 服务层发布闸与回执落库；HTTP 和数据库均使用边界替身，不执行真实发布。 */
@DisplayName("新闻发布服务：凭据闸与回执持久化")
class NewsWorkPublishReceiptTest {
    private static final long WORK_ID = 3L;
    private static final long VERSION_ID = 4L;
    private static final long CANDIDATE_ID = 2L;
    private static final long TASK_ID = 8L;
    private static final long ACTOR = 101L;
    private static final String VIDEO_SHA = "a".repeat(64);
    private static final String CONTACT_SHA = "b".repeat(64);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 30, 15, 123456789);

    @BeforeAll
    static void initializeConverters() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "publish-version"), HeronVideoVersion.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "publish-candidate"), HeronNewsCandidate.class);
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.register(SpringUtils.class, HeronVideoVersionToNewsVideoVersionVoMapperImpl.class,
            MapstructAutoConfiguration.class);
        context.refresh();
    }

    @Test
    @DisplayName("YouTube 缺凭据时 prepareConfirm 不创建发布尝试")
    void missingCredentialsDoNotCreatePublishAttempt() {
        HeronNewsProperties properties = new HeronNewsProperties();
        properties.setYoutubeEnabled(true);
        properties.setYoutubeClientId("test-client-id");
        properties.setYoutubeClientSecret(null);
        properties.setYoutubeRefreshToken("test-refresh-token");
        var fixture = new Fixture(new YouTubePublishAdapter(properties));

        var error = assertThrows(HeronBusinessException.class, () -> fixture.service.prepareConfirm(
            WORK_ID, VERSION_ID, fixture.approval(), ACTOR, false));

        assertEquals(503, error.getStatus());
        assertEquals("youtube_credentials_missing", error.getCode());
        assertTrue(error.getMessage().contains("youtube-client-secret"));
        verify(fixture.attemptMapper, never()).insert(any(HeronNewsPublishAttempt.class));
        verify(fixture.attemptMapper, never()).transition(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outlets")
    @DisplayName("成功回执持久化来源、规范链接、账号、视频指纹与 attempt 状态")
    void successfulReceiptPersistsPublishFacts(Outlet outlet) {
        var fixture = new Fixture(outlet);

        var prepared = fixture.service.prepareConfirm(WORK_ID, VERSION_ID, fixture.approval(), ACTOR, false);
        assertNotNull(prepared.request());
        assertEquals("started", fixture.storedAttempt.get().getStatus());
        var receipt = fixture.adapter.publish(prepared.request());
        NewsVideoVersionVo result = fixture.service.finishConfirm(WORK_ID, VERSION_ID, prepared.attemptId(), receipt, ACTOR, false);
        HeronNewsPublishAttempt attempt = fixture.storedAttempt.get();

        assertEquals("approved", result.getReviewStatus());
        assertEquals(outlet.source(), attempt.getSource());
        assertEquals(outlet.account(), attempt.getAccount());
        assertEquals(VIDEO_SHA, attempt.getVideoSha());
        assertEquals(outlet.attemptStatus(), attempt.getStatus());
        verify(fixture.adapter).publish(argThat(request -> VERSION_ID == request.versionId()
            && VIDEO_SHA.equals(request.videoSha256()) && outlet.account().equals(request.account())));
        verify(fixture.attemptMapper).transition(anyLong(), eq("started"), eq(outlet.attemptStatus()),
            eq(outlet.account()), isNull(), any(), isNull(), isNull(), isNull());

        if (outlet.versionPublished()) {
            assertEquals(outlet.source(), result.getPublishSource());
            assertEquals(outlet.workUrl(), result.getPublishUrl());
            assertEquals(outlet.account(), result.getPublishAccount());
            assertEquals(VIDEO_SHA, result.getPublishVideoSha());
            assertEquals(NOW.truncatedTo(ChronoUnit.MILLIS), result.getPublishAt());
            assertEquals(outlet.source(), fixture.version.getPublishSource());
            assertEquals(outlet.workUrl(), fixture.version.getPublishUrl());
            assertEquals(outlet.account(), fixture.version.getPublishAccount());
            assertEquals(VIDEO_SHA, fixture.version.getPublishVideoSha());
            assertEquals(result.getPublishAt(), fixture.candidate.getPublishedAt());
        } else {
            assertNull(result.getPublishSource());
            assertNull(result.getPublishUrl());
            assertNull(result.getPublishAccount());
            assertNull(result.getPublishVideoSha());
            assertNull(result.getPublishAt());
            assertNull(fixture.candidate.getPublishedAt());
            verify(fixture.versions, never()).update(any(Wrapper.class));
            verify(fixture.candidates, never()).update(any(Wrapper.class));
        }
    }

    @Test
    @DisplayName("CC_BY 署名从已校验 asset manifest 进入 YouTube 发布描述")
    void ccAttributionIsDeliveredInPublishDescription() throws Exception {
        Fixture fixture = new Fixture(new Outlet("youtube", "frozen-channel",
            "https://www.youtube.com/watch?v=aBcDeFgHiJk", "published", true));
        byte[] manifest = "{\"mediaSelections\":[{\"licenseCode\":\"CC_BY\",\"attribution\":\"示例作者\","
            .concat("\"sourceUri\":\"https://source.example.test/city\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ProductionArtifactVo assetManifest = new ProductionArtifactVo("manifest", "asset_manifest", "videos/manifest.json",
            manifest.length, cn.hutool.crypto.digest.DigestUtil.sha256Hex(manifest), "application/json");
        when(fixture.artifacts.openVerified(assetManifest)).thenReturn(new java.io.ByteArrayInputStream(manifest));
        fixture.version.setArtifactManifest(JsonMapper.builder().build().writeValueAsString(List.of(
            new ProductionArtifactVo("video", "mp4", "videos/final.mp4", 1, VIDEO_SHA, "video/mp4"),
            new ProductionArtifactVo("contact", "contact_sheet", "videos/contact.png", 1, CONTACT_SHA, "image/png"),
            assetManifest)));

        var prepared = fixture.service.prepareConfirm(WORK_ID, VERSION_ID, fixture.approval(), ACTOR, false);

        assertTrue(prepared.request().description().contains("示例作者"));
        assertTrue(prepared.request().description().contains("CC_BY"));
        assertTrue(prepared.request().description().contains("https://source.example.test/city"));
    }

    @Test
    @DisplayName("YouTube unknown 尝试可用合法 watch URL 人工收口为 published")
    void youtubeUnknownCanBeManuallyPublished() {
        Fixture fixture = new Fixture(new Outlet("youtube", "frozen-channel",
            "https://www.youtube.com/watch?v=aBcDeFgHiJk", "published", true));
        var prepared = fixture.service.prepareConfirm(WORK_ID, VERSION_ID, fixture.approval(), ACTOR, false);
        fixture.storedAttempt.get().setStatus("unknown");
        NewsPublishBo receipt = new NewsPublishBo("https://www.youtube.com/watch?v=aBcDeFgHiJk", "frozen-channel",
            NOW, VIDEO_SHA);
        var decision = new NewsPublishResolveBo("published", VIDEO_SHA, "运营核对平台已发布", true, receipt);

        NewsVideoVersionVo result = fixture.service.resolvePublishAttempt(WORK_ID, VERSION_ID, prepared.attemptId(), decision);

        assertEquals("youtube", result.getPublishSource());
        assertEquals(receipt.workUrl(), result.getPublishUrl());
        assertEquals("published", fixture.storedAttempt.get().getStatus());
    }

    @Test
    @DisplayName("YouTube 人工回执拒绝 URL 不匹配与账号指纹不匹配")
    void youtubeManualReceiptRejectsUrlAndAccountMismatch() {
        Fixture fixture = new Fixture(new Outlet("youtube", "frozen-channel",
            "https://www.youtube.com/watch?v=aBcDeFgHiJk", "published", true));
        var prepared = fixture.service.prepareConfirm(WORK_ID, VERSION_ID, fixture.approval(), ACTOR, false);
        fixture.storedAttempt.get().setStatus("unknown");
        NewsPublishBo wrongUrl = new NewsPublishBo("https://www.douyin.com/video/7400000000000000000", "frozen-channel", NOW, VIDEO_SHA);
        assertEquals("news_invalid_input", assertThrows(HeronBusinessException.class,
            () -> fixture.service.resolvePublishAttempt(WORK_ID, VERSION_ID, prepared.attemptId(),
                new NewsPublishResolveBo("published", VIDEO_SHA, "核对", true, wrongUrl))).getCode());
        NewsPublishBo wrongAccount = new NewsPublishBo("https://www.youtube.com/watch?v=aBcDeFgHiJk", "other", NOW, VIDEO_SHA);
        assertEquals("news_publish_account", assertThrows(HeronBusinessException.class,
            () -> fixture.service.resolvePublishAttempt(WORK_ID, VERSION_ID, prepared.attemptId(),
                new NewsPublishResolveBo("published", VIDEO_SHA, "核对", true, wrongAccount))).getCode());
        assertEquals("news_invalid_input", assertThrows(HeronBusinessException.class,
            () -> fixture.service.resolvePublishAttempt(WORK_ID, VERSION_ID, prepared.attemptId(),
                new NewsPublishResolveBo("published", "f".repeat(64), "核对", true, wrongAccount))).getCode());
        assertNull(fixture.version.getPublishAt());
    }

    @Test
    @DisplayName("YouTube unknown 人工确认 not_published 保留审计且不写发布事实")
    void youtubeUnknownCanBeResolvedAsNotPublished() {
        Fixture fixture = new Fixture(new Outlet("youtube", "frozen-channel",
            "https://www.youtube.com/watch?v=aBcDeFgHiJk", "published", true));
        var prepared = fixture.service.prepareConfirm(WORK_ID, VERSION_ID, fixture.approval(), ACTOR, false);
        fixture.storedAttempt.get().setStatus("unknown");

        fixture.service.resolvePublishAttempt(WORK_ID, VERSION_ID, prepared.attemptId(),
            new NewsPublishResolveBo("not_published", VIDEO_SHA, "上传已停止且平台无发布", true, null));

        assertEquals("not_published", fixture.storedAttempt.get().getStatus());
        assertNull(fixture.version.getPublishAt());
        assertNull(fixture.candidate.getPublishedAt());
    }

    private static Stream<Outlet> outlets() {
        return Stream.of(
            new Outlet("youtube", "frozen-channel", "https://www.youtube.com/watch?v=aBcDeFgHiJk", "published", true),
            new Outlet("sandbox", "sandbox", "sandbox://news/" + VERSION_ID, "published", true),
            new Outlet("douyin-cli", "heron", null, "submitted", false));
    }

    private record Outlet(String source, String account, String workUrl, String attemptStatus, boolean versionPublished) { }

    private static class Fixture {
        final IPublishAdapter adapter;
        final HeronNewsPublishAttemptMapper attemptMapper = mock(HeronNewsPublishAttemptMapper.class);
        final HeronVideoVersionMapper versions = mock(HeronVideoVersionMapper.class);
        final HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
        final HeronVideoVersion version = new HeronVideoVersion();
        final HeronNewsCandidate candidate = new HeronNewsCandidate();
        final AtomicReference<HeronNewsPublishAttempt> storedAttempt = new AtomicReference<>();
        final NewsWorkServiceImpl service;
        final ProductionArtifactService artifacts;

        Fixture(Outlet outlet) {
            this(adapterFor(outlet));
        }

        Fixture(IPublishAdapter adapter) {
            this.adapter = adapter;
            HeronNewsWork work = new HeronNewsWork();
            work.setId(WORK_ID);
            work.setCandidateId(CANDIDATE_ID);
            work.setOwnerId(ACTOR);
            work.setCurrentVersionId(VERSION_ID);
            candidate.setId(CANDIDATE_ID);

            HeronProductionTask task = new HeronProductionTask();
            task.setId(TASK_ID);
            task.setBizId(WORK_ID);
            task.setVersionId(VERSION_ID);
            task.setOwnerId(ACTOR);
            task.setStatus("succeeded");

            List<ProductionArtifactVo> files = List.of(
                new ProductionArtifactVo("video", "mp4", "videos/final.mp4", 1, VIDEO_SHA, "video/mp4"),
                new ProductionArtifactVo("contact", "contact_sheet", "videos/contact.png", 1, CONTACT_SHA, "image/png"));
            version.setId(VERSION_ID);
            version.setWorkId(WORK_ID);
            version.setOwnerId(ACTOR);
            version.setTaskId(TASK_ID);
            version.setScript("{\"title\":\"冻结标题\"}");
            version.setArtifactManifest(JsonMapper.builder().build().writeValueAsString(files));
            version.setChecks("[]");
            version.setDurationMs(1000L);
            version.setReviewStatus("approved");
            version.setReviewContactSha(CONTACT_SHA);
            version.setReviewVideoSha(VIDEO_SHA);
            version.setCreateTime(NOW);

            var works = mock(HeronNewsWorkMapper.class);
            var lanes = mock(HeronProductionLaneMapper.class);
            var tasks = mock(HeronProductionTaskMapper.class);
            var access = mock(HeronAccessService.class);
            var domain = mock(NewsProductionDomain.class);
            artifacts = mock(ProductionArtifactService.class);
            var clocks = new StaticListableBeanFactory(Map.of("clock",
                Clock.fixed(Instant.parse("2026-11-24T10:30:15.123456789Z"), ZoneOffset.UTC)))
                .getBeanProvider(Clock.class);

            when(access.requireActor()).thenReturn(ACTOR);
            when(candidates.selectById(CANDIDATE_ID)).thenReturn(candidate);
            when(works.selectById(WORK_ID)).thenReturn(work);
            when(versions.selectById(VERSION_ID)).thenReturn(version);
            when(versions.lockById(VERSION_ID)).thenReturn(version);
            when(lanes.lockByType(any())).thenReturn(new HeronProductionLane());
            when(tasks.selectById(TASK_ID)).thenReturn(task);
            when(tasks.lockById(TASK_ID)).thenReturn(task);
            when(artifacts.exists(any())).thenReturn(true);
            when(artifacts.localPath(any())).thenReturn(Path.of("fixture.mp4"));
            when(candidates.currentModelClass()).thenReturn(HeronNewsCandidate.class);
            when(versions.currentModelClass()).thenReturn(HeronVideoVersion.class);
            when(candidates.lambda()).thenAnswer(call -> new LambdaCrudChainWrapper<>(candidates));
            when(versions.lambda()).thenAnswer(call -> new LambdaCrudChainWrapper<>(versions));
            when(candidates.update(any(Wrapper.class))).thenAnswer(call -> {
                applyUpdate(candidate, (LambdaCrudChainWrapper<?, ?>) call.getArgument(0));
                return 1;
            });
            when(versions.update(any(Wrapper.class))).thenAnswer(call -> {
                applyUpdate(version, (LambdaCrudChainWrapper<?, ?>) call.getArgument(0));
                return 1;
            });

            when(attemptMapper.lockActive(CANDIDATE_ID)).thenReturn(null);
            when(attemptMapper.insert(any(HeronNewsPublishAttempt.class))).thenAnswer(call -> {
                storedAttempt.set(call.getArgument(0));
                return 1;
            });
            when(attemptMapper.lockById(anyLong())).thenAnswer(call -> storedAttempt.get());
            when(attemptMapper.transition(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    HeronNewsPublishAttempt attempt = storedAttempt.get();
                    if (!call.getArgument(1).equals(attempt.getStatus())) {
                        return 0;
                    }
                    attempt.setStatus(call.getArgument(2));
                    attempt.setAccount(call.getArgument(3));
                    attempt.setUpdatedAt(call.getArgument(5));
                    return 1;
                });
            service = new NewsWorkServiceImpl(candidates, works, versions, lanes, tasks, access, null, domain,
                null, artifacts, null, clocks, adapter, new HeronNewsProperties(),
                new NewsPublishAttempts(attemptMapper), null, null);
        }

        private static IPublishAdapter adapterFor(Outlet outlet) {
            IPublishAdapter adapter = mock(IPublishAdapter.class);
            when(adapter.source()).thenReturn(outlet.source());
            when(adapter.account()).thenReturn(outlet.account());
            when(adapter.publish(any())).thenAnswer(call -> {
                PublishRequest request = call.getArgument(0);
                return new PublishReceipt(outlet.workUrl(), outlet.account(), request.requestedAt(), outlet.source());
            });
            return adapter;
        }

        NewsReviewBo approval() {
            return new NewsReviewBo("approved", CONTACT_SHA, VIDEO_SHA, null);
        }
    }

    private static void applyUpdate(Object target, LambdaCrudChainWrapper<?, ?> wrapper) {
        Map<String, Object> parameters = wrapper.getParamNameValuePairs();
        Matcher assignment = Pattern.compile("(\\w+)=#\\{ew\\.paramNameValuePairs\\.([^}]+)}").matcher(wrapper.getSqlSet());
        while (assignment.find()) {
            String column = assignment.group(1);
            String property = Character.toLowerCase(column.charAt(0))
                + Pattern.compile("_([a-z])").matcher(column.substring(1)).replaceAll(match -> match.group(1).toUpperCase());
            new BeanWrapperImpl(target).setPropertyValue(property, parameters.get(assignment.group(2)));
        }
    }
}

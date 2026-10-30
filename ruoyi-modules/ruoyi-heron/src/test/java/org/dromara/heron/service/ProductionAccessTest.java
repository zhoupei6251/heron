package org.dromara.heron.service;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.IdUtil;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.controller.HeronExceptionHandler;
import org.dromara.heron.controller.ProductionController;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionQueryBo;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.ProductionReceiptVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.HeronAccessService;
import org.dromara.heron.service.impl.ProductionArtifactService;
import org.dromara.heron.service.impl.ProductionCapabilityService;
import org.dromara.heron.support.HeronMysqlTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("K4：任务中心 HTTP 权限、对象范围与证据")
class ProductionAccessTest extends HeronMysqlTestSupport {

    private static final long OWNER = 101L;
    private static final long OTHER = 102L;
    private static final long REVIEWER = 103L;
    private static final long NONE = 104L;
    private static final long ADMIN = SystemConstants.SUPER_ADMIN_USER_ID;

    @Autowired
    private IProductionService productionService;

    @Autowired
    private IProductionExecutionService executionService;

    @Autowired
    private ProductionCapabilityService capabilities;

    @Autowired
    private ProductionArtifactService artifacts;

    @Autowired
    private HeronAccessService access;

    @Autowired
    private HeronProductionProperties properties;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        resetSchema();
        clearProductionData();
        mvc = MockMvcBuilders.standaloneSetup(
                new ProductionController(productionService, capabilities, artifacts, access))
            .setControllerAdvice(new HeronExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("列表按对象范围过滤，跨用户按 ID 读取返回 404")
    void listIsScopedAndForeignTaskIsNotFound() throws Exception {
        long ownNews = createTask(OWNER, ProductionBizType.news_video, 11, "access-own");
        grantPermissions(OWNER, "heron:production:list", "heron:production:query",
            "heron:newsWork:generate");
        loginAs(OWNER);
        mvc.perform(get("/heron/production/list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.rows[0].bizId").value(11));
        mvc.perform(get("/heron/production/{taskId}", ownNews)).andExpect(status().isOk());

        long foreignNews = createTask(OTHER, ProductionBizType.news_video, 12, "access-foreign");
        long foreignProto = createTask(OTHER, ProductionBizType.prototype, 13, "access-foreign-proto");
        loginAs(OWNER);
        mvc.perform(get("/heron/production/{taskId}", foreignNews))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.msg").value("资源不存在"));

        loginAs(REVIEWER);
        grantPermissions(REVIEWER, "heron:production:list", "heron:production:query",
            "heron:proto:all");
        mvc.perform(get("/heron/production/list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.rows[0].bizId").value(13));
        mvc.perform(get("/heron/production/{taskId}", foreignProto)).andExpect(status().isOk());
        mvc.perform(get("/heron/production/{taskId}", foreignNews)).andExpect(status().isNotFound());

        loginAs(ADMIN);
        mvc.perform(get("/heron/production/list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get("/heron/production/{taskId}", foreignNews)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("非发起人取消需要业务生成权限")
    void reviewerWithoutGenerateCannotCancel() throws Exception {
        long taskId = createTask(OTHER, ProductionBizType.prototype, 14, "access-cancel");
        grantPermissions(REVIEWER, "heron:production:query", "heron:production:cancel",
            "heron:proto:all");
        loginAs(REVIEWER);
        mvc.perform(post("/heron/production/{taskId}/cancel", taskId))
            .andExpect(status().isNotFound());
        assertEquals("queued", jdbc.queryForObject(
            "SELECT status FROM heron_production_task WHERE task_id=?", String.class, taskId));

        loginAs(OTHER);
        grantPermissions(OTHER, "heron:production:query", "heron:production:cancel",
            "heron:proto:generate");
        mvc.perform(post("/heron/production/{taskId}/cancel", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("cancelled"));
    }

    @Test
    @DisplayName("视图不泄露令牌、快照和存储路径；证据可下载并在损坏/清理后拒绝")
    void viewAndEvidenceExposeNoInternals() throws Exception {
        EvidenceFixture fixture = createFailedEvidence(OWNER, 15, "access-evidence");
        grantPermissions(OWNER, "heron:production:query", "heron:newsWork:generate");
        loginAs(OWNER);

        MvcResult view = mvc.perform(get("/heron/production/{taskId}", fixture.taskId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("failed"))
            .andExpect(jsonPath("$.data.steps[0].evidence[0].artifactId")
                .value(fixture.artifactId()))
            .andReturn();
        String body = view.getResponse().getContentAsString();
        assertFalse(body.contains("requestKey"));
        assertFalse(body.contains("run_token"));
        assertFalse(body.contains("inputSnapshot"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("leaseUntil"));

        mvc.perform(get("/heron/production/{taskId}/evidence/{artifactId}",
                fixture.taskId(), fixture.artifactId()))
            .andExpect(status().isOk())
            .andExpect(content().bytes("diagnostic".getBytes(StandardCharsets.UTF_8)));

        loginAs(OTHER);
        grantPermissions(OTHER, "heron:production:query", "heron:newsWork:generate");
        mvc.perform(get("/heron/production/{taskId}/evidence/{artifactId}",
                fixture.taskId(), fixture.artifactId()))
            .andExpect(status().isNotFound());

        loginAs(OWNER);
        mvc.perform(get("/heron/production/{taskId}/evidence/{artifactId}",
                fixture.taskId(), UUID.randomUUID().toString()))
            .andExpect(status().isNotFound());

        Files.writeString(fixture.file(), "diagnosticX", StandardCharsets.UTF_8);
        mvc.perform(get("/heron/production/{taskId}/evidence/{artifactId}",
                fixture.taskId(), fixture.artifactId()))
            .andExpect(status().isConflict());

        Files.delete(fixture.file());
        mvc.perform(get("/heron/production/{taskId}/evidence/{artifactId}",
                fixture.taskId(), fixture.artifactId()))
            .andExpect(status().isGone());
    }

    @Test
    @DisplayName("能力接口要求任一业务查询权限，列表使用按钮权限注解")
    void capabilitiesRequireBusinessQueryPermission() throws Exception {
        grantPermissions(NONE, "heron:production:list");
        loginAs(NONE);
        mvc.perform(get("/heron/production/capabilities"))
            .andExpect(status().isNotFound());

        grantPermissions(NONE, "heron:newsWork:query");
        mvc.perform(get("/heron/production/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.news").exists())
            .andExpect(jsonPath("$.data.prototype").exists());

        SaCheckPermission annotation = ProductionController.class
            .getMethod("list", ProductionQueryBo.class, PageQuery.class)
            .getAnnotation(SaCheckPermission.class);
        assertTrue(annotation != null);
        assertTrue(String.join(",", annotation.value()).contains("heron:production:list"));
    }

    private long createTask(long owner, ProductionBizType type, long bizId, String key) {
        grantPermissions(owner, "heron:production:query", HeronAccessService.generatePermission(type));
        loginAs(owner);
        insertObject(bizId, type, owner);
        ProductionReceiptVo receipt = productionService.submit(new ProductionSubmitBo(type, bizId, key, 1));
        return receipt.taskId();
    }

    private EvidenceFixture createFailedEvidence(long owner, long bizId, String key) throws Exception {
        long taskId = createTask(owner, ProductionBizType.news_video, bizId, key);
        ProductionRunVo run = executionService.claim(ProductionBizType.news_video).orElseThrow();
        assertEquals(taskId, run.taskId());
        jdbc.update("UPDATE heron_production_task SET status='failed', run_token=NULL, lease_until=NULL "
            + "WHERE task_id=?", taskId);

        String artifactId = UUID.randomUUID().toString();
        byte[] content = "diagnostic".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(content));
        String evidence = "[{\"artifactId\":\"" + artifactId + "\",\"kind\":\"log\","
            + "\"storageKey\":\"work/news_video/" + taskId + "/a1/.keep/" + artifactId + ".txt\","
            + "\"bytes\":" + content.length + ",\"sha256\":\"" + sha256 + "\","
            + "\"mediaType\":\"text/plain\"}]";
        jdbc.update("""
                INSERT INTO heron_task_step
                  (step_id,task_id,attempt_no,stage,status,started_at,finished_at,evidence,
                   create_time,update_time)
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """,
            IdUtil.getSnowflakeNextId(), taskId, run.attemptNo(), "render", "failed", evidence);

        Path keep = properties.getDataRoot().toAbsolutePath().normalize()
            .resolve("work").resolve("news_video").resolve(Long.toString(taskId))
            .resolve("a" + run.attemptNo() + "-" + run.runToken()).resolve(".keep");
        Files.createDirectories(keep);
        Path file = keep.resolve(artifactId + ".txt");
        Files.write(file, content);
        return new EvidenceFixture(taskId, artifactId, file);
    }

    private record EvidenceFixture(long taskId, String artifactId, Path file) {
    }
}

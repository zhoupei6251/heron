package org.dromara.heron.support;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.*;
import org.dromara.heron.domain.vo.*;
import org.dromara.heron.mapper.*;
import org.dromara.heron.service.*;
import org.dromara.heron.service.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Files below are synthetic transaction evidence; no fixture establishes Axure editability. */
@ContextConfiguration(classes = ProtoMysqlTestConfiguration.class, inheritLocations = false)
public abstract class ProtoMysqlTestSupport extends HeronMysqlTestSupport {
    protected static final long OWNER = 201L;
    public static final String TARGET = "TEST-DECLARATION-ONLY";
    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired protected IProtoProjectService proto;
    @Autowired protected IProductionService production;
    @Autowired protected IProductionExecutionService execution;
    @Autowired protected HeronProtoProjectMapper projectMapper;
    @Autowired protected HeronProtoVersionMapper versionMapper;
    @Autowired protected HeronProductionTaskMapper taskMapper;
    @Autowired protected ProtoProductionDomain domain;
    @Autowired protected ProductionArtifactService artifacts;
    @Autowired protected HeronAccessService access;
    @Autowired protected HeronProductionProperties properties;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected MutableClock clock;

    @BeforeEach
    void resetProto() throws Exception {
        resetSchema();
        clearProductionData();
        jdbc.execute("DROP TABLE IF EXISTS heron_proto_version");
        jdbc.execute("DROP TABLE IF EXISTS heron_proto_project");
        Path ddl = Path.of("../../script/sql/heron/003-prototype.sql");
        if (!Files.isRegularFile(ddl)) ddl = Path.of("script/sql/heron/003-prototype.sql");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(ddl));
        }
        grantPermissions(OWNER, "heron:proto:add", "heron:proto:edit", "heron:proto:list", "heron:proto:query",
            "heron:proto:generate", "heron:proto:review", "heron:proto:verify", "heron:proto:deliver",
            "heron:proto:reviewPreview", "heron:proto:download", "heron:production:cancel");
        loginOwner();
    }

    protected void loginOwner() { loginAs(OWNER); }
    protected ProtoProjectBo draft(String requirement) {
        var bo = new ProtoProjectBo();
        bo.setName("事务测试");
        bo.setRequirement(requirement);
        bo.setCanvas("web");
        bo.setThemeKey("default");
        return bo;
    }
    protected long createProject() { return proto.insertByBo(draft("两页原型需求")).getProjectId(); }

    protected List<Object> race(Callable<?> a, Callable<?> b) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Object>();
            var futures = List.of(a, b).stream().map(call -> pool.submit((Callable<Object>) () -> {
                loginOwner();
                barrier.await(10, TimeUnit.SECONDS);
                try { return call.call(); } catch (HeronBusinessException e) {
                    if (e.getStatus() != 409) throw e;
                    return e;
                }
            })).toList();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            // 竞争线程的 loginAs 会注销共享令牌，主线程必须重新登录
            loginOwner();
            return results;
        }
    }

    protected ProtoFixture runningFixture() throws Exception {
        long id = createProject();
        var receipt = proto.generate(id, new ProtoGenerateBo(UUID.randomUUID().toString(), 1));
        var run = execution.claim(ProductionBizType.prototype).orElseThrow();
        if (run.taskId() != receipt.taskId()) throw new AssertionError("Fixture claimed an unrelated task");
        var files = new ArrayList<ProductionArtifactVo>();
        var rp1 = fixtureFile(run, "rp1", "application/json", "json", "{\"pages\":[{\"id\":\"home\"},{\"id\":\"detail\"}]}");
        files.add(rp1);
        var p1 = fixtureFile(run, "page_preview", "image/png", "png", "fixture-image-home");
        var p2 = fixtureFile(run, "page_preview", "image/png", "png", "fixture-image-detail");
        files.add(p1); files.add(p2);
        var summary = fixtureFile(run, "preview_summary", "image/png", "png", "fixture-image-summary");
        files.add(summary);
        var audit = fixtureFile(run, "audit", "application/json", "json", "{\"fixture\":\"not-real-engine-audit\"}");
        files.add(audit);
        var check = fixtureFile(run, "engine_check", "application/json", "json", "{\"fixture\":\"not-real-engine-check\"}");
        files.add(check);
        var rp = fixtureFile(run, "native_rp", "application/octet-stream", "rp", "fixture-only-not-an-Axure-file");
        files.add(rp);
        var evidence = new ProtoVersionVo.ManifestEvidence("a".repeat(40), Map.of("blank", "b".repeat(64)),
            List.of(new ProtoVersionVo.PageEvidence("home", p1.artifactId()),
                new ProtoVersionVo.PageEvidence("detail", p2.artifactId())),
            List.of("home", "detail"), rp1.artifactId(), summary.artifactId(), rp.artifactId(), check.artifactId(),
            true, true, rp1.sha256(), new ProtoVersionVo.AuditEvidence(true, true, true, true, audit.artifactId()));
        return new ProtoFixture(id, run, new ProductionResultVo(files, JSON.writeValueAsString(evidence)), rp, summary);
    }

    protected ProtoFixture succeededFixture() throws Exception {
        var f = runningFixture();
        assertTrue(execution.succeed(f.run(), f.result()));
        return f;
    }
    protected ProtoFixture approvedFixture() throws Exception {
        var f = succeededFixture();
        proto.review(f.projectId(), f.run().versionId(), reviewBo(f));
        return f;
    }
    protected ProtoReviewBo reviewBo(ProtoFixture f) {
        return new ProtoReviewBo("approved", f.rp().sha256(), f.summary().sha256(), null);
    }
    protected ProtoDeliverBo deliverBo(ProtoFixture f) {
        return new ProtoDeliverBo("delivery", f.rp().sha256(), f.summary().sha256());
    }
    protected ProtoNativeVerificationBo nativeBo(ProtoFixture f, String key) {
        return new ProtoNativeVerificationBo(key, f.rp().sha256(), TARGET,
            true, true, true, true, true, "测试声明，不是 Axure 证据");
    }
    protected Map<String, Object> deliveryRow(long versionId) {
        return jdbc.queryForMap("SELECT delivery_request_key,delivery_by,delivery_at,delivery_preview_sha,"
            + "delivery_rp_sha,delivery_native_verified,delivery_verification_id FROM heron_proto_version WHERE version_id=?",
            versionId);
    }
    private ProductionArtifactVo fixtureFile(ProductionRunVo run, String kind, String media, String extension,
                                            String content) throws Exception {
        String artifactId = UUID.randomUUID().toString();
        String key = "protos/" + run.bizId() + "/v1/a" + run.attemptNo() + "-" + run.runToken()
            + "/" + artifactId + "." + extension;
        Path file = properties.getDataRoot().resolve(key);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return new ProductionArtifactVo(artifactId, kind, key, Files.size(file), DigestUtil.sha256Hex(Files.readAllBytes(file)), media);
    }
    protected record ProtoFixture(long projectId, ProductionRunVo run, ProductionResultVo result,
                                  ProductionArtifactVo rp, ProductionArtifactVo summary) { }
}

package org.dromara.heron.service;

import org.dromara.heron.controller.HeronExceptionHandler;
import org.dromara.heron.controller.ProtoController;
import org.dromara.heron.support.ProtoMysqlTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProtoAccessTest extends ProtoMysqlTestSupport {

    private MockMvc mvc;

    @BeforeEach
    void web() {
        mvc = MockMvcBuilders.standaloneSetup(new ProtoController(proto))
            .setControllerAdvice(new HeronExceptionHandler()).build();
    }

    @Test
    void crossUserProjectVersionAndArtifactAllReturnReal404() throws Exception {
        var first = approvedFixture();
        var second = approvedFixture();
        long p = first.projectId();
        long v = first.run().versionId();
        String route = "/heron/proto/{p}/versions/{v}/review-download";
        mvc.perform(post(route, p, second.run().versionId()).param("artifactId", first.rp().artifactId()))
            .andExpect(status().isNotFound());
        mvc.perform(post(route, p, v).param("artifactId", second.rp().artifactId()))
            .andExpect(status().isNotFound());
        grantPermissions(202L, "heron:proto:query", "heron:proto:reviewPreview", "heron:proto:download");
        loginAs(202L);
        mvc.perform(get("/heron/proto/{p}", p)).andExpect(status().isNotFound());
        mvc.perform(post(route, p, v).param("artifactId", first.rp().artifactId()))
            .andExpect(status().isNotFound());
        grantPermissions(202L, "heron:proto:all", "heron:proto:query");
        mvc.perform(get("/heron/proto/{p}", p)).andExpect(status().isOk());
        mvc.perform(post(route, p, v).param("artifactId", first.rp().artifactId()))
            .andExpect(status().isNotFound());
    }

    @Test
    void reviewDownloadIsNotDeliveryAndFormalDownloadRequiresFrozenHashes() throws Exception {
        var f = approvedFixture();
        String route = "/heron/proto/{p}/versions/{v}";
        mvc.perform(post(route + "/review-download", f.projectId(), f.run().versionId())
                .param("artifactId", f.rp().artifactId()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-SHA256", f.rp().sha256()))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        assertNull(proto.version(f.projectId(), f.run().versionId()).getDelivery());
        mvc.perform(post(route + "/download", f.projectId(), f.run().versionId())
                .param("artifactId", f.rp().artifactId()).param("rpSha256", f.rp().sha256()))
            .andExpect(status().isConflict());
        proto.deliver(f.projectId(), f.run().versionId(), deliverBo(f));
        mvc.perform(post(route + "/download", f.projectId(), f.run().versionId())
                .param("artifactId", f.rp().artifactId()).param("rpSha256", "0".repeat(64)))
            .andExpect(status().isConflict());
        mvc.perform(post(route + "/download", f.projectId(), f.run().versionId())
                .param("artifactId", f.rp().artifactId()).param("rpSha256", f.rp().sha256()))
            .andExpect(status().isOk());
        mvc.perform(get(route + "/preview/{a}", f.projectId(), f.run().versionId(), f.rp().artifactId()))
            .andExpect(status().isNotFound());
        mvc.perform(get(route + "/preview/{a}", f.projectId(), f.run().versionId(), f.summary().artifactId()))
            .andExpect(status().isOk());
        String json = mvc.perform(get(route, f.projectId(), f.run().versionId())).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertFalse(json.contains("storageKey"));
        assertFalse(json.contains("runToken"));
        assertFalse(json.contains(properties.getDataRoot().toString()));
    }

    @Test
    void listCountUsesSqlScopeAndArchivedHistoryKeepsSameAuthorization() throws Exception {
        var f = approvedFixture();
        proto.archive(f.projectId());
        mvc.perform(get("/heron/proto/list")).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/heron/proto/list").param("archived", "1"))
            .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/heron/proto/{p}/versions/{v}", f.projectId(), f.run().versionId()))
            .andExpect(status().isOk());
        grantPermissions(202L, "heron:proto:list", "heron:proto:query");
        loginAs(202L);
        mvc.perform(get("/heron/proto/list").param("archived", "1"))
            .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/heron/proto/{p}/versions/{v}", f.projectId(), f.run().versionId()))
            .andExpect(status().isNotFound());
    }
}

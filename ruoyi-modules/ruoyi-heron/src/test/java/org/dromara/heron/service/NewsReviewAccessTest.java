package org.dromara.heron.service;

import org.dromara.heron.controller.HeronExceptionHandler;
import org.dromara.heron.controller.NewsCandidateController;
import org.dromara.heron.controller.NewsWorkController;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NewsReviewAccessTest extends NewsMysqlTestSupport {
    private MockMvc mvc;
    @BeforeEach
    void http() {
        mvc = MockMvcBuilders.standaloneSetup(new NewsWorkController(news), new NewsCandidateController(news))
            .setControllerAdvice(new HeronExceptionHandler()).build();
    }

    @Test
    void objectPermissionAndAssociationFailuresAreReal404() throws Exception {
        var f = completed();
        var another = work();
        mvc.perform(get("/heron/newsWork/{w}/versions/{v}", another.getWorkId(), f.versionId())).andExpect(status().isNotFound());
        mvc.perform(get("/heron/newsWork/{w}/versions/{v}/preview/{a}", f.workId(), f.versionId(), UUID.randomUUID())).andExpect(status().isNotFound());
        loginAs(OTHER);
        mvc.perform(get("/heron/newsWork/{w}", f.workId())).andExpect(status().isNotFound());
        mvc.perform(get("/heron/newsWork/list")).andExpect(jsonPath("$.data.total").value(0));
        grantPermissions(OTHER, "heron:newsWork:all", "heron:newsWork:query");
        mvc.perform(get("/heron/newsWork/{w}/versions/{v}", f.workId(), f.versionId())).andExpect(status().isOk());
        mvc.perform(get("/heron/newsWork/{w}/versions/{v}/preview/{a}", f.workId(), f.versionId(), f.file("mp4").artifactId())).andExpect(status().isNotFound());
    }

    @Test
    void reviewAndDownloadRequireBoundHashesCompleteFilesAndSeparatePermission() throws Exception {
        var f = completed();
        String path = "/heron/newsWork/" + f.workId() + "/versions/" + f.versionId();
        mvc.perform(post(path + "/download").param("artifactId", f.file("mp4").artifactId()).param("videoSha256", f.file("mp4").sha256())).andExpect(status().isConflict());
        mvc.perform(post(path + "/review").contentType("application/json").content("{\"decision\":\"rejected\",\"contactSha256\":\"" + f.file("contact_sheet").sha256()
            + "\",\"videoSha256\":\"" + f.file("mp4").sha256() + "\",\"reason\":\" \"}")).andExpect(status().isBadRequest());
        news.review(f.workId(), f.versionId(), f.approve());
        var response = mvc.perform(post(path + "/download").param("artifactId", f.file("mp4").artifactId()).param("videoSha256", f.file("mp4").sha256()))
            .andExpect(status().isOk()).andExpect(header().string("X-Content-SHA256", f.file("mp4").sha256()))
            .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        assertArrayEquals(Files.readAllBytes(properties.getDataRoot().resolve(f.file("mp4").storageKey())), response.getContentAsByteArray());
        String body = mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains(properties.getDataRoot().toString()));
        mvc.perform(post(path + "/download").param("artifactId", f.file("contact_sheet").artifactId()).param("videoSha256", f.file("mp4").sha256())).andExpect(status().isNotFound());
        Files.writeString(properties.getDataRoot().resolve(f.file("script").storageKey()), "corrupt");
        mvc.perform(post(path + "/download").param("artifactId", f.file("mp4").artifactId()).param("videoSha256", f.file("mp4").sha256())).andExpect(status().isConflict());
    }

    @Test
    void symlinkAndMissingFilesCannotBePreviewed() throws Exception {
        var f = completed();
        var video = properties.getDataRoot().resolve(f.file("mp4").storageKey());
        var outside = Files.createTempFile("heron_it_news_outside_", ".mp4");
        Files.copy(video, outside, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.delete(video);
        String path = "/heron/newsWork/" + f.workId() + "/versions/" + f.versionId() + "/preview/" + f.file("mp4").artifactId();
        mvc.perform(get(path)).andExpect(status().isGone());
        // Requires symlink permission (Linux or Windows Developer Mode); absence is a test failure, not a skip.
        Files.createSymbolicLink(video, outside);
        mvc.perform(get(path)).andExpect(status().isConflict());
        Files.delete(video);
        Files.delete(outside);
    }

    @Test
    void missingPipelineIs503AndMalformedManualIs400() throws Exception {
        var w = work();
        mvc.perform(post("/heron/newsWork/{w}/generate", w.getWorkId()).contentType("application/json")
            .content("{\"requestKey\":\"missing\",\"expectedRevision\":1}")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/heron/newsCandidate/manual").contentType("application/json")
            .content("{\"title\":\"   \",\"summary\":\"事实\"}")).andExpect(status().isBadRequest());
    }
}

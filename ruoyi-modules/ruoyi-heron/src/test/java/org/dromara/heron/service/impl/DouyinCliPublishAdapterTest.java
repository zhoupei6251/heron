package org.dromara.heron.service.impl;

import org.dromara.heron.service.impl.shared.publish.DouyinCliPublishAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.time.LocalDateTime;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.PublishRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 命令行参数与 social-auto-upload 的 upload-video 契约对齐；契约变了这里先红。 */
@DisplayName("抖音命令行发布出口")
class DouyinCliPublishAdapterTest {

    @Test
    @DisplayName("upload-video 参数按 CLI 契约排列，路径走独立参数不拼 shell")
    void commandFollowsCliContract() {
        Path file = Path.of("data", "work", "final cut.mp4").toAbsolutePath();
        assertEquals(List.of("sau", "douyin", "upload-video", "--account", "heron",
                "--file", file.toString(), "--title", "标题 带空格"),
            DouyinCliPublishAdapter.command("sau", "heron", file, "标题 带空格"));
    }

    @Test
    void uploadUsesTheAccountFrozenWithTheAttempt() {
        var properties = new HeronNewsProperties();
        properties.setPublishAccount("changed-config");
        var adapter = new DouyinCliPublishAdapter(properties);
        var request = new PublishRequest(1, 2, "标题", "a".repeat(64), LocalDateTime.now(), null, "approved-account");
        assertEquals("approved-account", adapter.targetAccount(request));
        properties.setPublishAccount("another-config");
        assertEquals("approved-account", adapter.targetAccount(request));
    }
}

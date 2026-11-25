package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板资产锁缺失时的报错要说准：目录在但没锁，和目录整个不在（模板已删），要做的事不一样。
 * 后者跑去生成锁是修不好的——那套模板已经不存在了。
 */
class NewsTemplateLockMessageTest {
    @TempDir
    Path root;

    private NewsProductionDomain domain() {
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setNewsTemplate(root);
        return new NewsProductionDomain(null, null, null, null, null, null, null, properties, null);
    }

    private static String messageOf(NewsProductionDomain domain, String templateKey) {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> domain.lockedAssets(templateKey));
        assertEquals(503, error.getStatus());
        assertEquals("news_assets_unconfigured", error.getCode());
        return error.getMessage();
    }

    @Test
    @DisplayName("目录在但缺 LOCK.json：指引去生成锁")
    void missingLockPointsAtLockScript() throws IOException {
        Files.createDirectories(root.resolve("news-9x16"));

        String message = messageOf(domain(), "news-9x16");

        assertTrue(message.contains("template-lock.mjs"), message);
    }

    @Test
    @DisplayName("目录不存在（模板已删）：说模板不存在、要重选版式，不让人去跑生成锁的脚本")
    void deletedTemplateSaysRepick() {
        String message = messageOf(domain(), "news-poster-9x16");

        assertTrue(message.contains("news-poster-9x16"), message);
        assertTrue(message.contains("不存在"), message);
        assertTrue(message.contains("重新选版式"), message);
        assertTrue(!message.contains("template-lock.mjs"), message);
    }
}

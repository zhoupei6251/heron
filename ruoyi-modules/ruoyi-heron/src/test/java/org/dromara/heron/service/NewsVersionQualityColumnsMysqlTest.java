package org.dromara.heron.service;

import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本行上的两列：质量审稿报告与内容属性。
 *
 * <p>它们都是辅助层：写了就落库，没写也不阻塞版本完成，更不进自动发布门禁。
 */
@DisplayName("版本行：质量报告与内容属性")
class NewsVersionQualityColumnsMysqlTest extends NewsMysqlTestSupport {

    @Autowired private HeronVideoVersionMapper versionMapper;

    @Test
    @DisplayName("出片后质量报告与内容属性一并落库")
    void qualityColumnsArePersisted() throws Exception {
        Completed completed = completed();

        HeronVideoVersion version = versionMapper.selectById(completed.versionId());
        assertNotNull(version.getQualityReport(), "质量报告没有落库");
        assertTrue(version.getQualityReport().contains("quality-review/1"), version.getQualityReport());
        assertNotNull(version.getContentProfile(), "内容属性没有落库");
        assertTrue(version.getContentProfile().contains("hookType"), version.getContentProfile());
    }
}

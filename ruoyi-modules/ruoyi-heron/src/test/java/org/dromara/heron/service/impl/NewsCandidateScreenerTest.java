package org.dromara.heron.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 抽卡结果的判定：只认候选里真实存在的 id，"0" 等于今天不选题。 */
class NewsCandidateScreenerTest {
    private static final List<Long> IDS = List.of(11L, 22L, 33L);

    @Test
    void acceptsOnlyRealCandidateIds() {
        assertEquals(22L, NewsCandidateScreener.choose(IDS, "{\"candidate_id\":22,\"reason\":\"降价\",\"score\":80}"));
        assertEquals(33L, NewsCandidateScreener.choose(IDS, "```json\n{\"candidate_id\":33}\n```"));
        assertNull(NewsCandidateScreener.choose(IDS, "{\"candidate_id\":0}"), "0 表示今天不选");
        assertNull(NewsCandidateScreener.choose(IDS, "{\"candidate_id\":99}"), "不在候选里的 id 一律不认");
        assertNull(NewsCandidateScreener.choose(IDS, "我觉得选 22"), "不是 JSON 就当没选");
        assertNull(NewsCandidateScreener.choose(IDS, null));
    }
}

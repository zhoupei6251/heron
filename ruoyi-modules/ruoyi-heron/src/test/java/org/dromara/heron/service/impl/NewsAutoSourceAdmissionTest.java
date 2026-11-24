package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronNewsCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动池的源准入：以前按 host 比，永远匹配不上。
 *
 * <p>根因是两种 host 本来就不同域：源地址是 {@code newsnow.busiyi.world}，
 * 而条目链接指向原站 {@code s.weibo.com}。按 host 比的结果是自动池恒为空——
 * 库里 15 条槽位全是 manual-*，没有一条是 cron 跑出来的。
 *
 * <p>现在按采集时写进 evidence 的 sourceId 判，host 只给没写 sourceId 的旧数据兜底。
 */
@DisplayName("自动池准入：按 sourceId 判，不再按 host")
class NewsAutoSourceAdmissionTest {

    private static final long AUTO_SOURCE = 920013L;
    private static final Set<Long> AUTO_IDS = Set.of(AUTO_SOURCE);
    private static final Set<String> AUTO_HOSTS = Set.of("newsnow.busiyi.world");

    private static HeronNewsCandidate candidate(String evidence) {
        HeronNewsCandidate entity = new HeronNewsCandidate();
        entity.setCandidateId(1L);
        entity.setEvidence(evidence);
        return entity;
    }

    private static String item(long sourceId, String url) {
        return "{\"items\":[{\"sourceId\":" + sourceId + ",\"sourceUrl\":\"" + url + "\"}],\"evidenceTruncated\":false}";
    }

    @Test
    @DisplayName("开了自动的源采到的候选进池，哪怕链接指向别的域")
    void candidateFromAutoSourceIsAdmitted() {
        assertTrue(NewsAutoService.fromAutoSource(
            candidate(item(AUTO_SOURCE, "https://s.weibo.com/weibo?q=x")), AUTO_IDS, AUTO_HOSTS),
            "sourceId 命中即为准入：条目链接在原站是正常的");
    }

    @Test
    @DisplayName("没开自动的源采到的候选不进池，哪怕同一个 host")
    void candidateFromManualSourceIsRejected() {
        assertFalse(NewsAutoService.fromAutoSource(
            candidate(item(920004L, "https://wallstreetcn.com/a")), AUTO_IDS, AUTO_HOSTS),
            "同域不等于同源：这是可下架的边界，不是权重");
    }

    @Test
    @DisplayName("老候选没写 sourceId 时退回 host 比，不至于升级后全部掉出池")
    void legacyCandidateFallsBackToHost() {
        String legacy = "{\"items\":[{\"sourceUrl\":\"https://newsnow.busiyi.world/api/s?id=weibo\"}]}";
        assertTrue(NewsAutoService.fromAutoSource(candidate(legacy), AUTO_IDS, AUTO_HOSTS));
    }

    @Test
    @DisplayName("多源合并的候选：任一条来自自动源就准入")
    void mergedCandidateAdmitsIfAnyItemMatches() {
        String merged = "{\"items\":[{\"sourceId\":920004},{\"sourceId\":" + AUTO_SOURCE + "}]}";
        assertTrue(NewsAutoService.fromAutoSource(candidate(merged), AUTO_IDS, AUTO_HOSTS),
            "同一话题被多个源采到是常态，命中一个就够");
    }

    @Test
    @DisplayName("evidence 读不出来时不放行：证明不了来源就不进自动池")
    void unreadableEvidenceIsRejected() {
        assertFalse(NewsAutoService.fromAutoSource(candidate("not-json"), AUTO_IDS, AUTO_HOSTS));
        assertFalse(NewsAutoService.fromAutoSource(candidate(null), AUTO_IDS, AUTO_HOSTS));
    }
}

package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronProductionArtifact;
import org.dromara.heron.mapper.HeronProductionArtifactMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 正文的冻结出入口：首次执行抓一次、存一份，之后的重试与续做都读这一份。
 *
 * <p>为什么必须落库而不是「每次现抓」：外部网页会改版、会撤稿、会 404。
 * 同一任务重试读到不同正文，等于同一份冻结上下文跑到一半换了输入——
 * 这比抓取失败更糟，因为它不报错，只产出一条和首次尝试不一致的片子。
 *
 * <p>只写一次：同一 task 同一 kind 有唯一键，先写者胜，后来者读已存在的那份。
 */
@Service
@RequiredArgsConstructor
public class FrozenArticleService {

    static final String KIND_ARTICLE = "article";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HeronProductionArtifactMapper mapper;
    private final NewsSourceFetcher fetcher;

    /** 写稿用的正文：已冻结的直接用，没有就按输入解析出处、抓一次并冻结。 */
    public NewsArticleBody of(long taskId, JsonNode input) {
        NewsArticleBody frozen = article(taskId);
        if (frozen != null) {
            return frozen;
        }
        NewsArticleBody fetched = fetcher.fetchArticle(articleUrl(input));
        return taskId <= 0 ? fetched : freeze(taskId, fetched);
    }

    /** 已冻结的正文；没有则返回 null。摘要对不上即拒绝，不拿被改过的正文写稿。 */
    public NewsArticleBody article(long taskId) {
        HeronProductionArtifact row = byTask(taskId);
        if (row == null) {
            return null;
        }
        if (!DigestUtil.sha256Hex(row.getContent()).equals(row.getContentSha256())) {
            throw new HeronBusinessException(409, "news_artifact_mismatch",
                "冻结正文与记录摘要不一致，拒绝使用");
        }
        return parse(row.getContent());
    }

    /**
     * 冻结正文。并发下先写者胜：撞唯一键就回读已存在的那份，保证两边拿到同一字节。
     */
    @Transactional(rollbackFor = Exception.class)
    public NewsArticleBody freeze(long taskId, NewsArticleBody body) {
        String content = render(body);
        HeronProductionArtifact row = new HeronProductionArtifact();
        row.setArtifactId(IdUtil.getSnowflakeNextId());
        row.setTaskId(taskId);
        row.setKind(KIND_ARTICLE);
        row.setSourceUrl(body.url());
        row.setContent(content);
        row.setContentSha256(DigestUtil.sha256Hex(content));
        row.setContentBytes(content.getBytes(StandardCharsets.UTF_8).length);
        row.setFetchedAt(body.fetchedAt());
        row.setCreateTime(now());
        row.setUpdateTime(now());
        try {
            mapper.insert(row);
            return body;
        } catch (DuplicateKeyException e) {
            NewsArticleBody existing = article(taskId);
            if (existing == null) {
                throw e;
            }
            return existing;
        }
    }

    /**
     * 正文出处：自动路径必须命中「线内编辑选中的证据」，命中不了就失败——
     * 退回第一条等于「快照记的是 A，写稿读的正文是 B」。
     * 手工路径没有 selection 节点，按 legacy 规则取第一条带链接的证据。
     */
    static URI articleUrl(JsonNode input) {
        List<String> selected = new ArrayList<>();
        for (JsonNode id : input.path("selection").path("sourceEvidenceIds")) {
            String text = id.asString("");
            if (!text.isBlank()) {
                selected.add(text);
            }
        }
        URI legacy = null;
        for (JsonNode item : input.path("evidence").path("items")) {
            String url = item.path("sourceUrl").asString("");
            if (url.isBlank()) {
                continue;
            }
            if (legacy == null) {
                legacy = articleUri(url);
            }
            String itemId = item.path("sourceItemId").asString("");
            if (selected.contains(itemId) || selected.contains(url)) {
                return articleUri(url);
            }
        }
        if (input.hasNonNull("selection")) {
            throw new HeronBusinessException(409, "news_script_source", "选中的证据无法定位正文，不能写稿");
        }
        if (legacy == null) {
            throw new HeronBusinessException(409, "news_script_source", "候选证据里没有正文出处，不能写稿");
        }
        return legacy;
    }

    private static URI articleUri(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new HeronBusinessException(409, "news_script_source", "候选证据里的正文链接无法解析");
        }
    }

    private HeronProductionArtifact byTask(long taskId) {
        if (taskId <= 0) {
            return null;
        }
        return mapper.selectOne(QueryBuilder.lambda(HeronProductionArtifact.class)
            .eq(HeronProductionArtifact::getTaskId, taskId)
            .eq(HeronProductionArtifact::getKind, KIND_ARTICLE)
            .last("LIMIT 1").build());
    }

    private static String render(NewsArticleBody body) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("url", body.url());
        node.put("fetchedAt", body.fetchedAt() == null ? null : body.fetchedAt().toString());
        node.put("html", body.html());
        node.put("htmlSha256", body.htmlSha256());
        return JSON.writeValueAsString(node);
    }

    private static NewsArticleBody parse(String content) {
        JsonNode node = JSON.readTree(content);
        String fetchedAt = node.path("fetchedAt").asString("");
        return new NewsArticleBody(
            node.path("url").asString(""),
            fetchedAt.isBlank() ? null : LocalDateTime.parse(fetchedAt),
            node.path("html").asString(""),
            node.path("htmlSha256").asString(""));
    }

    /** 落库时间固定按 UTC：与采集侧同一口径，不依赖会话时钟。 */
    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

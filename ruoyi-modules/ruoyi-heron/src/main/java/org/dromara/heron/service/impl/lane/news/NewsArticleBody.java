package org.dromara.heron.service.impl.lane.news;

import java.time.LocalDateTime;

/**
 * 正文抓取产出的出处三要素与正文本体。
 *
 * @param url        规范链接，冻结稿的 source_url
 * @param fetchedAt  抓取时间，冻结稿的 fetched_at
 * @param html       正文 HTML，供写稿与配图抽取
 * @param htmlSha256 整篇文档 SHA-256，冻结稿的 body_sha256
 */
public record NewsArticleBody(String url, LocalDateTime fetchedAt, String html, String htmlSha256) { }

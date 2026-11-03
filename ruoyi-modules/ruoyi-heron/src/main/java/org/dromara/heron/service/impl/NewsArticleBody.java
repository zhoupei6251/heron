package org.dromara.heron.service.impl;

import java.time.LocalDateTime;

/** 正文抓取产出的出处三要素：规范链接、抓取时间、整篇文档哈希。 */
public record NewsArticleBody(String url, LocalDateTime fetchedAt, String html, String htmlSha256) { }

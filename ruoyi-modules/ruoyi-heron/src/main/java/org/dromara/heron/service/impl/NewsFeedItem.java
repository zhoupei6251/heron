package org.dromara.heron.service.impl;

/** 抓取到的原始条目；URL 与 itemId 可为空，rank 为来源内 1 起始的名次。 */
public record NewsFeedItem(String title, String summary, String url, String itemId, Integer rank) {
}

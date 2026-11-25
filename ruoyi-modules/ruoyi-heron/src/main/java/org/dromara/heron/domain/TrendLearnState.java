package org.dromara.heron.domain;

import java.util.Locale;

/** 热点视频学习状态；LEARNED 是不可逆终态。 */
public enum TrendLearnState {

    SEEN,
    SELECTED,
    DOWNLOADED,
    DECOMPOSED,
    LEARNED,
    DOWNLOAD_FAILED,
    DECOMPOSE_FAILED,
    LEARN_FAILED;

    public static TrendLearnState of(String value) {
        if (value == null || value.isBlank()) {
            return SEEN;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public boolean retryable() {
        return this == SEEN || this == DOWNLOAD_FAILED || this == DECOMPOSE_FAILED || this == LEARN_FAILED;
    }

    public boolean terminal() {
        return this == LEARNED;
    }
}

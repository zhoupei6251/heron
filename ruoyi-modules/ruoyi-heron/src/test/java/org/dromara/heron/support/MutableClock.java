package org.dromara.heron.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用 UTC 可拨动时钟，便于推进租约。
 */
public class MutableClock extends Clock {

    private LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);

    public void advanceSeconds(long seconds) {
        now = now.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("测试时钟不支持切换时区");
    }

    @Override
    public Instant instant() {
        return now.toInstant(ZoneOffset.UTC);
    }

    @Override
    public long millis() {
        return instant().toEpochMilli();
    }
}

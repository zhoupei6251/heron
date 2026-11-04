package org.dromara.heron.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("审片预览的 Range 解析")
class NewsRangeTest {

    private static final long TOTAL = 3_328_660L;

    @Test
    @DisplayName("无 Range 或写法不认识时回整段")
    void absentOrUnknownIsFullBody() {
        assertNull(NewsWorkController.rangeOf(null, TOTAL));
        assertNull(NewsWorkController.rangeOf("bytes=0-1,4-5", TOTAL));
        assertNull(NewsWorkController.rangeOf("bytes=abc-def", TOTAL));
        assertNull(NewsWorkController.rangeOf("bytes=99999999999999999999999999-", TOTAL));
        assertArrayEquals(new long[] {0, TOTAL - 1}, NewsWorkController.rangeOf("bytes=0-", TOTAL));
    }

    @Test
    @DisplayName("区间按闭区间与末尾后缀解析")
    void closedAndSuffixRanges() {
        assertArrayEquals(new long[] {1000, 1999}, NewsWorkController.rangeOf("bytes=1000-1999", TOTAL));
        assertArrayEquals(new long[] {1000, TOTAL - 1}, NewsWorkController.rangeOf("bytes=1000-", TOTAL));
        assertArrayEquals(new long[] {TOTAL - 500, TOTAL - 1}, NewsWorkController.rangeOf("bytes=-500", TOTAL));
        assertArrayEquals(new long[] {0, TOTAL - 1}, NewsWorkController.rangeOf("bytes=-" + TOTAL * 2, TOTAL));
        assertArrayEquals(new long[] {0, TOTAL - 1}, NewsWorkController.rangeOf("bytes=0-99999999999", TOTAL));
    }

    @Test
    @DisplayName("空区间、倒置和越界都判不满足")
    void unsatisfiable() {
        assertArrayEquals(new long[] {-1, -1}, NewsWorkController.rangeOf("bytes=-", TOTAL));
        assertArrayEquals(new long[] {-1, -1}, NewsWorkController.rangeOf("bytes=-0", TOTAL));
        assertArrayEquals(new long[] {-1, -1}, NewsWorkController.rangeOf("bytes=200-100", TOTAL));
        assertArrayEquals(new long[] {-1, -1}, NewsWorkController.rangeOf("bytes=" + TOTAL + "-", TOTAL));
    }
}

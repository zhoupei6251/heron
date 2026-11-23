package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 数据集取窗必须逐日轮换：字母序截断会永久饿死排在后面的源（geo 验收时实际发生）。 */
class NewsAutoDatasetWindowTest {

    private static List<HeronDataSource> sources(int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            HeronDataSource source = new HeronDataSource();
            source.setSourceKey(String.format("source-%03d", i));
            return source;
        }).toList();
    }

    @Test
    @DisplayName("不足上限时原样返回")
    void smallListUnchanged() {
        List<HeronDataSource> all = sources(5);
        assertSame(all, NewsAutoService.datasetWindow(all, 327, 20));
    }

    @Test
    @DisplayName("超上限时取满且同一天结果确定")
    void windowSizeAndDeterminism() {
        List<HeronDataSource> all = sources(94);
        List<HeronDataSource> a = NewsAutoService.datasetWindow(all, 327, 20);
        List<HeronDataSource> b = NewsAutoService.datasetWindow(all, 327, 20);
        assertEquals(20, a.size());
        assertEquals(a, b);
    }

    @Test
    @DisplayName("连续若干天的窗口并集覆盖全部源：没有源被永久饿死")
    void rotationCoversEveryone() {
        List<HeronDataSource> all = sources(94);
        Set<String> seen = new HashSet<>();
        for (int day = 1; day <= 10; day++) {
            NewsAutoService.datasetWindow(all, day, 20)
                .forEach(source -> seen.add(source.getSourceKey()));
        }
        assertEquals(94, seen.size());
        assertTrue(seen.contains("source-093"), "字母序最后的源也必须轮到");
    }
}
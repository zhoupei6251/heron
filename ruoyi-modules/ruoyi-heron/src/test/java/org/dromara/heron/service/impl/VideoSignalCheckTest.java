package org.dromara.heron.service.impl;

import org.dromara.heron.service.impl.shared.quality.VideoSignalCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 画面信号检测：黑场与冻结段的解析。 */
@DisplayName("成片信号：黑屏与冻结帧")
class VideoSignalCheckTest {

    /** 实测 ffmpeg 输出样式：blackdetect 一行打完，freezedetect 分三行打。 */
    private static final String LOG = """
        [Parsed_blackdetect_0 @ 0000021ada84ec80] black_start:4 black_end:7 black_duration:3
        [Parsed_freezedetect_1 @ 0000021ada84ed80] lavfi.freezedetect.freeze_start: 0
        [Parsed_freezedetect_1 @ 0000021ada84ed80] lavfi.freezedetect.freeze_duration: 4
        [Parsed_freezedetect_1 @ 0000021ada84ed80] lavfi.freezedetect.freeze_end: 4
        [Parsed_freezedetect_1 @ 0000021ada84ed80] lavfi.freezedetect.freeze_start: 4
        """;

    @Test
    @DisplayName("黑场与冻结段都从日志里解析出来")
    void parsesBlackAndFreeze() {
        VideoSignalCheck.Result result = VideoSignalCheck.parse(LOG, 12.0);
        assertEquals(1, result.black().size());
        assertEquals(4.0, result.black().get(0).start(), 0.001);
        assertEquals(7.0, result.black().get(0).end(), 0.001);
        assertEquals(3.0, result.longestBlack(), 0.001);
        // 两段冻结：0–4 有明确的 end，4 之后那段视频在冻结中结束
        assertEquals(2, result.freeze().size());
        assertEquals(4.0, result.freeze().get(0).seconds(), 0.001);
        assertEquals(8.0, result.longestFreeze(), 0.001);
    }

    @Test
    @DisplayName("视频在冻结中结束：那段按成片时长收尾")
    void freezeRunningToTheEndClosesAtVideoLength() {
        VideoSignalCheck.Result result = VideoSignalCheck.parse(LOG, 12.0);
        VideoSignalCheck.Span last = result.freeze().get(result.freeze().size() - 1);
        assertEquals(4.0, last.start(), 0.001);
        assertEquals(12.0, last.end(), 0.001);
        assertEquals(8.0, last.seconds(), 0.001);
    }

    @Test
    @DisplayName("干净成片：滤镜没报任何一段")
    void cleanVideoHasNoSpans() {
        String log = """
            Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'final.mp4':
              Duration: 00:00:38.10, start: 0.000000, bitrate: 2000 kb/s
            [Parsed_blackdetect_0 @ 0000021ada84ec80] blackdetect d=1.000000 pic_th=0.980000 pix_th=0.030000
            """;
        VideoSignalCheck.Result result = VideoSignalCheck.parse(log, 38.1);
        assertTrue(result.black().isEmpty());
        assertTrue(result.freeze().isEmpty());
        assertEquals(0.0, result.longestFreeze(), 0.001);
        assertEquals(0.0, result.longestBlack(), 0.001);
    }

    @Test
    @DisplayName("有黑场就是致命的：整段纯黑，画面上没有内容")
    void blackFrameIsBlocking() {
        VideoSignalCheck.Result result = VideoSignalCheck.parse(LOG, 12.0);
        VideoSignalCheck.Span black = VideoSignalCheck.blockingBlack(result);
        assertNotNull(black);
        assertEquals(4.0, black.start(), 0.001);
        assertEquals(7.0, black.end(), 0.001);
    }

    @Test
    @DisplayName("干净成片没有致命黑场")
    void cleanVideoHasNoBlockingBlack() {
        String log = """
            Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'final.mp4':
              Duration: 00:00:38.10, start: 0.000000, bitrate: 2000 kb/s
            """;
        VideoSignalCheck.Result result = VideoSignalCheck.parse(log, 38.1);
        assertNull(VideoSignalCheck.blockingBlack(result));
    }

    @Test
    @DisplayName("只有冻结帧不拦：静帧有时是刻意的表达")
    void freezeAloneIsNotBlocking() {
        String log = """
            [Parsed_freezedetect_1 @ 0000021ada84ed80] lavfi.freezedetect.freeze_start: 0
            [Parsed_freezedetect_1 @ 0000021ada84ed80] lavfi.freezedetect.freeze_duration: 4
            [Parsed_freezedetect_1 @ 0000021ada84ed80] lavfi.freezedetect.freeze_end: 4
            """;
        VideoSignalCheck.Result result = VideoSignalCheck.parse(log, 12.0);
        assertNull(VideoSignalCheck.blockingBlack(result));
        assertEquals(1, result.freeze().size());
    }
}

package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 响度口径：解析、增益换算与交付判定的口径。 */
@DisplayName("成片响度口径")
class AudioLoudnessTest {

    /** ffmpeg loudnorm 打印出来的真实格式。 */
    private static final String REAL_OUTPUT = """
        [Parsed_loudnorm_0 @ 000001e654c] 
        {
        	"input_i" : "-19.50",
        	"input_tp" : "-2.80",
        	"input_lra" : "5.20",
        	"input_thresh" : "-29.80",
        	"output_i" : "-16.02",
        	"output_tp" : "-4.38",
        	"output_lra" : "5.30",
        	"output_thresh" : "-26.43",
        	"normalization_type" : "dynamic",
        	"target_offset" : "0.02"
        }
        """;

    @Test
    @DisplayName("从 loudnorm 输出里取实测值")
    void parsesMeasuredValues() {
        AudioLoudness.Measurement measurement = AudioLoudness.parse(REAL_OUTPUT);

        assertEquals(-19.50, measurement.integratedLufs(), 0.001);
        assertEquals(-2.80, measurement.truePeakDb(), 0.001);
        assertEquals(5.20, measurement.lra(), 0.001);
    }

    @Test
    @DisplayName("日志里别处也有花括号时，按 input_i 定位仍然取对")
    void parsesDespiteOtherBracesInLog() {
        String noisy = "[Parsed_loudnorm_0 @ 0x7f00] filter config {level: 32}\n" + REAL_OUTPUT;

        assertEquals(-19.50, AudioLoudness.parse(noisy).integratedLufs(), 0.001);
    }

    @Test
    @DisplayName("量不到就报错，不把缺数据当成零响度")
    void rejectsUnmeasurableOutput() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> AudioLoudness.parse("[Parsed_loudnorm_0] 没有实测值"));
        assertEquals("news_audio_loudness_failed", error.getCode());

        assertThrows(HeronBusinessException.class,
            () -> AudioLoudness.parse("{\"input_i\":\"-inf\",\"input_tp\":\"-inf\"}"));
    }

    @Test
    @DisplayName("旁白按实测响度反算增益")
    void computesVoiceGain() {
        AudioLoudness.Measurement voice = new AudioLoudness.Measurement(-19.5, -2.8, 5.2, -29.8);

        // −16 −(−19.5) = +3.5 dB
        assertEquals(3.5, AudioLoudness.voiceGain(voice), 0.001);
        assertEquals(1.496236, AudioLoudness.factor(AudioLoudness.voiceGain(voice)), 0.000001);
    }

    @Test
    @DisplayName("配乐目标比成片低 12 dB")
    void computesBgmGain() {
        AudioLoudness.Measurement bgm = new AudioLoudness.Measurement(-22.9, -13.0, 2.6, -32.0);

        // −28 −(−22.9) = −5.1 dB
        assertEquals(-5.1, AudioLoudness.bgmGain(bgm), 0.001);
        assertEquals(-28.0, AudioLoudness.TARGET_LUFS + AudioLoudness.BGM_OFFSET_LUFS, 0.001);
    }

    @Test
    @DisplayName("交付判定：响度在 ±1 内且真峰值不越界")
    void judgesDeliverySpec() {
        assertTrue(new AudioLoudness.Measurement(-16.0, -3.0, 5, -26).deliveryOk());
        assertTrue(new AudioLoudness.Measurement(-15.0, -1.5, 5, -26).deliveryOk());
        assertTrue(new AudioLoudness.Measurement(-17.0, -2.0, 5, -26).deliveryOk());

        // 超出 ±1
        assertFalse(new AudioLoudness.Measurement(-14.9, -3.0, 5, -26).deliveryOk());
        assertFalse(new AudioLoudness.Measurement(-17.1, -3.0, 5, -26).deliveryOk());
        // 真峰值越界
        assertFalse(new AudioLoudness.Measurement(-16.0, -1.4, 5, -26).deliveryOk());
        assertFalse(new AudioLoudness.Measurement(-15.74, -1.45, 5, -26).deliveryOk());
        // 响度合格但峰值越界，同样不算达标
        assertFalse(new AudioLoudness.Measurement(-16.0, 0.12, 5, -26).deliveryOk());
    }

    @Test
    @DisplayName("限幅器预留真峰值余量到 −1.8 dBFS，且不做自动电平补偿")
    void limiterHoldsPeakWithoutAutoLevel() {
        String limiter = AudioLoudness.limiter();

        assertTrue(limiter.contains("alimiter=limit=0.812831"), limiter);
        assertTrue(limiter.contains("level=disabled"), limiter);
        assertEquals(-1.5, AudioLoudness.MAX_TRUE_PEAK, 0.000001);
        assertEquals(0.812831, AudioLoudness.LIMIT, 0.000001);
    }
}

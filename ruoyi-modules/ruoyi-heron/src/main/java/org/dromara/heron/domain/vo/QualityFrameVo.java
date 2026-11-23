package org.dromara.heron.domain.vo;

/**
 * 成片抽帧：给人看的画面证据（L-1）。
 *
 * <p>以 data URL 直接回给页面，不落库、不当产物——帧只回答「这一段屏幕上到底是什么」，
 * 看完即弃。它属于主观审片的辅助证据，不参与任何闸。
 *
 * @param seq     序号，1 起
 * @param label   画面出处（第几段 / 结尾）
 * @param atMs    抽帧时间点，取自字幕闸实测区间
 * @param dataUrl data:image/jpeg;base64 形式的缩略图
 */
public record QualityFrameVo(int seq, String label, long atMs, String dataUrl) {
}

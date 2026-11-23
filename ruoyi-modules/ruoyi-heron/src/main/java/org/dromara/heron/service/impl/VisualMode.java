package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialStory;

/**
 * 数据看板线的视觉表达模式。
 *
 * <p>2D 是默认；3D 只服务有空间关系的选题（{@code shape=geo}），
 * 不是「好看的都上 3D」——一旦 3D 成为默认，成本和出错面都会失控。
 *
 * <p>模式由机器按数据形态定，不交给模型：模型只会挑看着顺眼的，
 * 不判断这条数据画得出来画不出来。
 */
public enum VisualMode {
    TWO_D("2d"),
    THREE_D("3d");

    private final String key;

    VisualMode(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** 模板登记里的模式；认不出来一律按 2D，3D 必须是显式声明。 */
    public static VisualMode ofKey(String key) {
        return "3d".equalsIgnoreCase(key == null ? "" : key.strip()) ? THREE_D : TWO_D;
    }

    /**
     * 一条数据故事该用哪种画面。
     *
     * <p>{@code basis.signalType} 在 geo 形态下被写成 {@code "geo"}（见 {@code DatasetStoryPlanner}），
     * 那是唯一带经纬度的形态，也是唯一能诚实做三维空间的形态；其余保持 2D。
     */
    public static VisualMode of(EditorialStory story) {
        String signal = story == null || story.basis() == null ? null : story.basis().signalType();
        return "geo".equals(signal) ? THREE_D : TWO_D;
    }
}

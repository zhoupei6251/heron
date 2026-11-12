package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronAutoSlot;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一次自动运行的可追溯视图：它看到了什么、选了哪条线、产出了哪条作品。 */
@Data
@AutoMapper(target = HeronAutoSlot.class)
public class EditorialRunVo implements Serializable {
    private LocalDate slotDate;
    private String slotTime;
    private String status;
    private String detail;
    private String selectedLine;
    private String editorialIntent;
    private String decisionReason;
    private Long candidateId;
    /** 决策当时的候选概况快照，原样返回给页面解析。 */
    private String poolJson;
    private Long workId;
    /** 产出作品的标题，供列表直接显示，避免页面再查一次。 */
    private String workTitle;
    private String workReviewStatus;
    /** 线内编辑在总编辑定线之后选了什么：候选、模板、角度、数据源。 */
    private String selectionJson;
    /** 这份产出的 AI 审稿记录，与自动发布门禁读的是同一份。 */
    private String reviewJson;
    private LocalDateTime updateTime;
}

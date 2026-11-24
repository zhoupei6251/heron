package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronResearchSnapshot;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 研究快照展示对象。
 *
 * <p>不含 {@code snapshotJson}：列表页与证据轨迹要的是「哪个源、哪一版、什么时候取的」，
 * 原文按 id 单独取，避免每次列表都拖着几 KB 的 JSON。
 */
@Data
@AutoMapper(target = HeronResearchSnapshot.class)
public class ResearchSnapshotVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long researchId;
    private Integer attemptNo;
    private String sourceKey;
    private String dataSha256;
    private String retrievedAt;
    private String contract;
    private Integer points;
    private LocalDateTime createTime;
}

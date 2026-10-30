package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronNewsCandidate;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 候选、热度与原始证据。 */
@Data
@AutoMapper(target = HeronNewsCandidate.class)
public class NewsCandidateVo implements Serializable {
    private Long candidateId;
    private String title;
    private String summary;
    private String evidence;
    private Long hitCount;
    private Long heatScore;
    private Integer bestRank;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private Long claimedBy;
    private LocalDateTime claimedAt;
    private Long activeTaskId;
    private LocalDateTime publishedAt;
}

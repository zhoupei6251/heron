package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronTrendHotword;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 抖音热词展示对象。
 */
@Data
@AutoMapper(target = HeronTrendHotword.class)
public class TrendHotwordVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String platform;
    private String word;
    private Integer rankNo;
    private Integer bestRank;
    private Long hotValue;
    private Long viewCount;
    private Long videoCount;
    private Long hitCount;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
}

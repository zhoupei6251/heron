package org.dromara.heron.domain;

import lombok.Getter;

import java.io.Serial;

/**
 * 明确业务失败，携带受限 HTTP 状态与稳定错误码。
 * ServiceException 为 final，无法继承，因此由 Heron 控制器范围自行映射。
 */
@Getter
public class HeronBusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public HeronBusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}

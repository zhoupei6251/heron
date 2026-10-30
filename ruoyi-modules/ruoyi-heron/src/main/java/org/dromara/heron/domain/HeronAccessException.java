package org.dromara.heron.domain;

import java.io.Serial;

/**
 * 对象不存在或越权，统一由 Heron 控制器映射为 HTTP 404。
 */
public class HeronAccessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public HeronAccessException() {
        super("资源不存在");
    }
}

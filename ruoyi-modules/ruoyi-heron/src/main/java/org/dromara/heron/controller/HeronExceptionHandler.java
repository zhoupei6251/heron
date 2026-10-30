package org.dromara.heron.controller;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.heron.domain.HeronAccessException;
import org.dromara.heron.domain.HeronBusinessException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 只接管 Heron 控制器的明确异常，其余异常保持基座兜底语义。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "org.dromara.heron.controller")
public class HeronExceptionHandler {

    @ExceptionHandler(HeronAccessException.class)
    public ResponseEntity<R<Void>> handleAccess(HeronAccessException e) {
        return ResponseEntity.status(404).body(R.fail(404, "资源不存在"));
    }

    @ExceptionHandler(HeronBusinessException.class)
    public ResponseEntity<R<Void>> handleBusiness(HeronBusinessException e) {
        log.warn("生产业务失败: status={}, code={}", e.getStatus(), e.getCode());
        return ResponseEntity.status(e.getStatus()).body(R.fail(e.getStatus(), e.getMessage()));
    }
}

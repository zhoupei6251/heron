package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.service.impl.NewsAutoService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 自动产出开关：查看状态、随时暂停/恢复；改 cron 仍走部署配置。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/auto")
public class HeronAutoController extends BaseController {
    private final NewsAutoService auto;
    private final HeronNewsProperties news;

    @SaCheckPermission("heron:production:query")
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", news.isAutoEnabled());
        status.put("paused", auto.paused());
        status.put("confirmEnabled", news.isAutoConfirmEnabled());
        status.put("slots", news.getAutoSlots());
        return R.ok(status);
    }

    @SaCheckPermission("heron:newsWork:review")
    @Log(title = "自动产出", businessType = BusinessType.UPDATE)
    @PostMapping("/run")
    public R<Map<String, Object>> run() {
        String slot = "manual-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        auto.tick(slot, true);
        var row = auto.slot(slot);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("slot", slot);
        result.put("status", row == null ? null : row.getStatus());
        result.put("detail", row == null ? null : row.getDetail());
        result.put("workId", row == null ? null : row.getWorkId());
        return R.ok(result);
    }

    @SaCheckPermission("heron:newsWork:review")
    @Log(title = "自动产出", businessType = BusinessType.UPDATE)
    @PostMapping("/pause")
    public R<Void> pause() {
        auto.pause(true);
        return R.ok();
    }

    @SaCheckPermission("heron:newsWork:review")
    @Log(title = "自动产出", businessType = BusinessType.UPDATE)
    @PostMapping("/resume")
    public R<Void> resume() {
        auto.pause(false);
        return R.ok();
    }
}

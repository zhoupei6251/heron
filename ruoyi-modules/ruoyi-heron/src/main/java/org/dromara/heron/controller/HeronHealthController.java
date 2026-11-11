package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.domain.vo.HeronHealthVo;
import org.dromara.heron.service.IHeronHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 操作台底部状态条的数据来源。只读，按生产查询权限可见。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/health")
public class HeronHealthController extends BaseController {
    private final IHeronHealthService health;

    @SaCheckPermission("heron:production:query")
    @GetMapping("/overview")
    public R<HeronHealthVo> overview() {
        return R.ok(health.overview());
    }
}

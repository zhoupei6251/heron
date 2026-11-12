package org.dromara.heron.controller;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.domain.bo.EditorialRunQuery;
import org.dromara.heron.domain.vo.EditorialRunVo;
import org.dromara.heron.service.impl.EditorialRunService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 编辑运行只读视图：看每天为什么走这条线、盘子里有什么。写入只走自动任务本身。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/editorial")
public class EditorialController extends BaseController {

    private final EditorialRunService runs;

    @GetMapping("/runs")
    public R<PageResult<EditorialRunVo>> list(EditorialRunQuery query) {
        return R.ok(runs.queryPageList(query));
    }

    /** 槽位时刻就是 cron 表达式，含空格与星号，只能走查询参数不能进路径。 */
    @GetMapping("/runs/detail")
    public R<EditorialRunVo> detail(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate slotDate,
                                    @RequestParam String slotTime) {
        return R.ok(runs.queryOne(slotDate, slotTime));
    }
}

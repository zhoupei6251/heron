package org.dromara.heron.controller;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.domain.vo.HeronDataSourceVo;
import org.dromara.heron.service.impl.DataSourceCatalogService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 数据源注册表：只读。登记与授权变更走 SQL 迁移，不在这里开写口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/dataSource")
public class DataSourceController extends BaseController {

    private final DataSourceCatalogService catalog;

    @GetMapping("/list")
    public R<PageResult<HeronDataSourceVo>> list(@RequestParam(required = false) String category,
                                                 @RequestParam(required = false) String commercialOk,
                                                 @RequestParam(required = false) String probeStatus,
                                                 PageQuery page) {
        return R.ok(catalog.queryPageList(category, commercialOk, probeStatus, page));
    }

    @GetMapping("/{sourceKey}")
    public R<HeronDataSourceVo> get(@PathVariable String sourceKey) {
        return R.ok(catalog.queryByKey(sourceKey));
    }
}

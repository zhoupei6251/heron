package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.vo.HeronDataSourceVo;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.springframework.stereotype.Service;

/**
 * 数据源注册表只读视图。注册走 SQL 迁移，页面不提供写入——
 * 授权与端点由人审过再进库，不能让后台随手加一个源。
 */
@Service
@RequiredArgsConstructor
public class DataSourceCatalogService {

    private final HeronDataSourceMapper dataSourceMapper;
    private final HeronAccessService access;

    public PageResult<HeronDataSourceVo> queryPageList(String category, String commercialOk, String probeStatus,
                                                       PageQuery page) {
        access.requirePermission("heron:dataSource:list");
        var wrapper = QueryBuilder.lambda(HeronDataSource.class)
            .eq(StringUtils.isNotBlank(category), HeronDataSource::getCategory, category)
            .eq(StringUtils.isNotBlank(commercialOk), HeronDataSource::getCommercialOk, commercialOk)
            .eq(StringUtils.isNotBlank(probeStatus), HeronDataSource::getProbeStatus, probeStatus)
            .orderByAsc(HeronDataSource::getCategory, HeronDataSource::getName)
            .build();
        var rows = dataSourceMapper.selectVoPage(page.build(), wrapper);
        return PageResult.build(rows.getRecords(), rows.getTotal());
    }

    public HeronDataSourceVo queryByKey(String sourceKey) {
        access.requirePermission("heron:dataSource:query");
        HeronDataSource source = dataSourceMapper.selectByKey(sourceKey);
        if (source == null) {
            throw new HeronBusinessException(404, "data_source_not_found", "数据源不存在");
        }
        return dataSourceMapper.selectVoById(source.getSourceKey());
    }
}

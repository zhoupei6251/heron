package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronReferenceAsset;
import org.dromara.heron.domain.HeronReferenceAssetToReferenceAssetVoMapperImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("参考素材映射：主键必须进入列表与操作响应")
class ReferenceAssetMappingTest {

    @Test
    void mapsReferenceAssetPrimaryKey() throws Exception {
        HeronReferenceAsset asset = new HeronReferenceAsset();
        asset.setId(42L);

        var vo = new HeronReferenceAssetToReferenceAssetVoMapperImpl().convert(asset);

        // 主键统一叫 id（V62）：用反射按名字取，防止字段改名后映射悄悄丢值
        assertEquals(42L, vo.getClass().getMethod("getId").invoke(vo));
    }
}

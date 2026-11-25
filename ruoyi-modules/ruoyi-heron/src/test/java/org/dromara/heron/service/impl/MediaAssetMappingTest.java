package org.dromara.heron.service.impl;

import org.dromara.heron.domain.MediaAsset;
import org.dromara.heron.domain.MediaAssetToMediaAssetVoMapperImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("素材映射：主键、方向与治理字段进入响应")
class MediaAssetMappingTest {

    @Test
    void mapsPersistentFieldsToVo() {
        MediaAsset asset = new MediaAsset();
        asset.setId(42L);
        asset.setOrientation("PORTRAIT");
        asset.setLicenseCode("CC_BY");

        var vo = new MediaAssetToMediaAssetVoMapperImpl().convert(asset);

        assertEquals(42L, vo.getId());
        assertEquals("PORTRAIT", vo.getOrientation());
        assertEquals("CC_BY", vo.getLicenseCode());
    }
}

package org.dromara.system.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.api.domain.OssDTO;
import org.dromara.system.domain.SysOss;
import org.dromara.system.mapper.SysOssMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SysOssRegistrationTest {

    private final SysOssMapper mapper = mock(SysOssMapper.class);
    private final SysOssServiceImpl service = new SysOssServiceImpl(mapper);

    @BeforeEach
    void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), SysOss.class);
    }

    @Test
    void registersAlreadyUploadedObjectWithItsStorageIdentity() {
        OssDTO uploaded = uploaded();

        service.registerUploaded(uploaded);

        ArgumentCaptor<SysOss> row = ArgumentCaptor.forClass(SysOss.class);
        verify(mapper).insert(row.capture());
        assertEquals("qiniu", row.getValue().getService());
        assertEquals("videos/7/v1/a1-object.mp4", row.getValue().getFileName());
        assertEquals("object.mp4", row.getValue().getOriginalName());
        assertEquals(".mp4", row.getValue().getFileSuffix());
        assertEquals("https://bucket.example/object.mp4", row.getValue().getUrl());
        assertEquals("{\"source\":\"heron\"}", row.getValue().getExt1());
    }

    @Test
    void repeatedRegistrationDoesNotInsertDuplicateMetadata() {
        when(mapper.selectOne(any())).thenReturn(new SysOss());

        service.registerUploaded(uploaded());

        verify(mapper, never()).insert(any(SysOss.class));
    }

    @Test
    void unregistersOnlyTheMatchingServiceAndObjectKey() {
        when(mapper.delete(any())).thenReturn(1);

        service.unregisterUploaded("qiniu", "videos/7/v1/a1-object.mp4");

        ArgumentCaptor<LambdaQueryWrapper<SysOss>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).delete(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("service ="));
        assertTrue(query.getValue().getSqlSegment().contains("file_name ="));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("qiniu"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("videos/7/v1/a1-object.mp4"));
    }

    @Test
    void blankStorageIdentityCannotTriggerBroadMetadataDeletion() {
        assertThrows(ServiceException.class, () -> service.unregisterUploaded("", "object.mp4"));
        assertThrows(ServiceException.class, () -> service.unregisterUploaded("qiniu", " "));
        verifyNoInteractions(mapper);
    }

    @Test
    void listsOnlyExpiredHeronKeysForTheConfiguredService() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 26, 0, 0);
        SysOss first = new SysOss();
        first.setFileName("videos/7/v1/a1-object.mp4");
        SysOss second = new SysOss();
        second.setFileName("protos/8/v2/a1-object.rp");
        when(mapper.selectList(any())).thenReturn(List.of(first, second));

        List<String> keys = service.listUploadedKeysBefore("qiniu", "heron", cutoff, 200);

        assertEquals(List.of("videos/7/v1/a1-object.mp4", "protos/8/v2/a1-object.rp"), keys);
        ArgumentCaptor<LambdaQueryWrapper<SysOss>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("service ="));
        assertTrue(sql.contains("create_time <"));
        assertTrue(sql.contains("JSON_VALID(ext1)"));
        assertTrue(sql.contains("JSON_EXTRACT(ext1, '$.source')"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("qiniu"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("heron"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(cutoff));
        assertTrue(query.getValue().getSqlSegment().contains("limit 200"));
    }

    @Test
    void expiredKeyQueryRejectsBlankIdentityAndInvalidLimit() {
        assertThrows(ServiceException.class, () -> service.listUploadedKeysBefore("", "heron", LocalDateTime.now(), 200));
        assertThrows(ServiceException.class, () -> service.listUploadedKeysBefore("qiniu", "", LocalDateTime.now(), 200));
        assertThrows(ServiceException.class, () -> service.listUploadedKeysBefore("qiniu", "heron", LocalDateTime.now(), 0));
        verifyNoInteractions(mapper);
    }

    private static OssDTO uploaded() {
        OssDTO dto = new OssDTO();
        dto.setService("qiniu");
        dto.setFileName("videos/7/v1/a1-object.mp4");
        dto.setOriginalName("object.mp4");
        dto.setFileSuffix(".mp4");
        dto.setUrl("https://bucket.example/object.mp4");
        dto.setExt1("{\"source\":\"heron\"}");
        return dto;
    }
}

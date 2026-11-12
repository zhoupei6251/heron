package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronVideoVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编辑策划只回 selection 那一小块。
 * 整份快照里正文与证据占 76 KB——之前把它塞进列表行，一页十行近 1 MB。
 */
@DisplayName("编辑运行视图：只回 selection，不搬整份快照")
class EditorialSelectionViewTest {

    private static HeronVideoVersion version(String snapshot) {
        HeronVideoVersion version = new HeronVideoVersion();
        version.setInputSnapshot(snapshot);
        return version;
    }

    @Test
    @DisplayName("从快照里取出线内编辑的选择")
    void extractsSelection() {
        String json = EditorialRunService.selectionJson(version("""
            {"kind":"article","selection":{"candidateId":42,"templateId":"news-quote-9x16",
             "storyAngle":"取舍之间","sourceEvidenceIds":["e1"],"digest":false},
             "evidence":{"items":[{"body":"这一坨不能跟着出去"}]}}
            """));
        assertNotNull(json);
        assertTrue(json.contains("news-quote-9x16"), json);
        assertTrue(json.contains("取舍之间"), json);
        assertFalse(json.contains("这一坨不能跟着出去"), "证据正文不该跟着出去：" + json);
    }

    @Test
    @DisplayName("没有 selection 的老快照返回 null，页面据此不渲染这一段")
    void missingSelectionIsNull() {
        assertNull(EditorialRunService.selectionJson(version("""
            {"kind":"data_board","title":"旧版本"}
            """)));
        assertNull(EditorialRunService.selectionJson(version(null)));
        assertNull(EditorialRunService.selectionJson(version("")));
    }
}

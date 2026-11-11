package org.dromara.heron.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 按源解析所属栏目并取对应题材包；新增源只归栏目，不新增 prompt。 */
final class ColumnPacks {

    private ColumnPacks() { }

    static Column columnOf(String sourceKey) {
        return Column.forSource(sourceKey);
    }

    static String text(String sourceKey) {
        Column column = Column.forSource(sourceKey);
        String path = "/prompt/data-board/columns/" + column.key() + ".md";
        try (InputStream stream = ColumnPacks.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("缺少栏目包 " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("栏目包不可读 " + path, e);
        }
    }
}

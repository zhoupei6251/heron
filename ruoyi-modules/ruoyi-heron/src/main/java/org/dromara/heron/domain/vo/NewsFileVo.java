package org.dromara.heron.domain.vo;

import java.io.IOException;
import java.io.InputStream;

/** 仅供 controller 消费的已校验打开句柄，不序列化到 JSON。 */
public record NewsFileVo(String fileName, String mediaType, long bytes, String sha256,
                         InputStream content) implements AutoCloseable {
    @Override public void close() throws IOException { content.close(); }
}

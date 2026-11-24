package org.dromara.heron.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;

/** 参考视频出网接缝：调用方负责逐跳策略，实现只连接已钉定的公网地址。 */
public interface ReferenceVideoTransport extends AutoCloseable {

    Response open(Request request) throws IOException;

    record Request(URI url, InetAddress pinned) { }

    record Response(int status, String location, String contentType, long contentLength, InputStream body)
        implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (body != null) {
                body.close();
            }
        }
    }

    @Override
    default void close() throws IOException { }
}

package org.dromara.heron.service.impl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;

/** 来源抓取接缝：由调用方给出已验证的主机与钉定的地址，实现只负责一次出网。 */
public interface NewsTransport {

    Response get(Request request) throws IOException;

    /** pinned 为策略刚验证过的地址；实现必须连接到它，同时保持原 host 的 TLS 校验。 */
    record Request(URI url, InetAddress pinned) { }

    record Response(int status, String location, String contentEncoding, String contentType, byte[] body) { }

    /** 每次连接前解析；同一主机在不同跳会被重复调用。 */
    interface Resolver {
        List<InetAddress> resolve(String host) throws IOException;
    }
}

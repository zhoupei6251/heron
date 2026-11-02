package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;

import java.net.InetAddress;
import java.util.List;

/** 来源地址策略：只允许公网可路由地址出网，私网与保留段一律在连接前拒绝。 */
public final class NewsAddressPolicy {

    private NewsAddressPolicy() { }

    public static boolean isPublic(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length == 16) {
            byte[] mapped = ipv4Mapped(raw);
            if (mapped != null) return !isReservedIpv4(mapped);
            // IPv6 只接受全局单播 2000::/3；环回、ULA、链路本地、组播、丢弃段全部拒绝。
            return (raw[0] & 0xE0) == 0x20;
        }
        return raw.length == 4 && !isReservedIpv4(raw);
    }

    /** 任一地址非公网即整次解析失败：半私网名字与 DNS 重绑定都走这条路。 */
    public static List<InetAddress> requirePublic(List<InetAddress> resolved, String host) {
        if (resolved == null || resolved.isEmpty() || resolved.stream().anyMatch(a -> !isPublic(a))) {
            throw new HeronBusinessException(400, "news_source_blocked", "来源地址不可公开访问: " + host);
        }
        return resolved;
    }

    private static boolean isReservedIpv4(byte[] a) {
        return matches(a, new byte[] {0, 0, 0, 0}, 8) || matches(a, new byte[] {10, 0, 0, 0}, 8)
            || matches(a, new byte[] {100, 64, 0, 0}, 10) || matches(a, new byte[] {127, 0, 0, 0}, 8)
            || matches(a, new byte[] {(byte) 169, (byte) 254, 0, 0}, 16)
            || matches(a, new byte[] {(byte) 172, 16, 0, 0}, 12)
            || matches(a, new byte[] {(byte) 192, 0, 0, 0}, 24) || matches(a, new byte[] {(byte) 192, 0, 2, 0}, 24)
            || matches(a, new byte[] {(byte) 192, 88, 99, 0}, 24)
            || matches(a, new byte[] {(byte) 192, (byte) 168, 0, 0}, 16)
            || matches(a, new byte[] {(byte) 198, 18, 0, 0}, 15) || matches(a, new byte[] {(byte) 224, 0, 0, 0}, 4)
            || matches(a, new byte[] {(byte) 240, 0, 0, 0}, 4);
    }

    private static boolean matches(byte[] address, byte[] network, int prefix) {
        for (int i = 0; i < 4; i++) {
            int mask = prefix <= i * 8 ? 0 : prefix >= (i + 1) * 8 ? 0xFF : (0xFF << (8 - (prefix - i * 8))) & 0xFF;
            if ((address[i] & mask) != (network[i] & mask)) return false;
        }
        return true;
    }

    private static byte[] ipv4Mapped(byte[] raw) {
        if (raw[10] != (byte) 0xff || raw[11] != (byte) 0xff) return null;
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) return null;
        }
        byte[] v4 = new byte[4];
        System.arraycopy(raw, 12, v4, 0, 4);
        return v4;
    }
}

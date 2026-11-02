package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** 来源地址结构策略：协议、端口、userinfo、国际化域名与跟踪参数归一。 */
public final class NewsUrlPolicy {

    private NewsUrlPolicy() { }

    /** 解析并规范化为仅含 ASCII 主机、无 fragment、端口只允许协议默认值的 URI。 */
    public static URI normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        int schemeEnd = value.indexOf("://");
        if (schemeEnd <= 0) throw new HeronBusinessException(400, "news_source_scheme", "来源地址必须使用 http 或 https");
        String scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new HeronBusinessException(400, "news_source_scheme", "来源地址只允许 http 或 https");
        }
        String rest = value.substring(schemeEnd + 3);
        int boundary = rest.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int at = rest.indexOf(c);
            if (at >= 0) boundary = Math.min(boundary, at);
        }
        String authority = rest.substring(0, boundary);
        String tail = rest.substring(boundary);
        if (authority.indexOf('@') >= 0) throw url("来源地址不得携带用户名或口令");
        int bracket = authority.indexOf(']');
        int colon = bracket > 0 ? authority.indexOf(':', bracket) : authority.indexOf(':');
        String hostPart = colon < 0 ? authority : authority.substring(0, colon);
        int port = -1;
        if (colon >= 0) {
            String portText = authority.substring(colon + 1);
            if (!portText.matches("\\d{1,5}")) throw url("来源端口不合法");
            port = Integer.parseInt(portText);
            if (port != (scheme.equals("https") ? 443 : 80)) {
                throw new HeronBusinessException(400, "news_source_port", "来源地址只允许 80 或 443 端口");
            }
            port = -1;
        }
        if (hostPart.isEmpty()) throw url("来源地址缺少主机名");
        // IPv6 字面量必须保留方括号才能拼出合法 URI；比较与解析时用 hostOf 去掉。
        boolean bracketed = hostPart.startsWith("[") && hostPart.endsWith("]");
        String host = bracketed ? hostPart.toLowerCase(Locale.ROOT) : ascii(hostPart);
        String path = tail, query = null;
        int mark = tail.indexOf('?');
        if (mark >= 0) {
            path = tail.substring(0, mark);
            int end = tail.indexOf('#', mark);
            query = end < 0 ? tail.substring(mark + 1) : tail.substring(mark + 1, end);
        } else {
            int fragment = tail.indexOf('#');
            if (fragment >= 0) path = tail.substring(0, fragment);
        }
        if (path.isEmpty()) path = "/";
        try {
            return new URI(scheme, null, host, port, path, query == null || query.isEmpty() ? null : query, null);
        } catch (URISyntaxException e) {
            throw url("来源地址无法解析");
        }
    }

    public static boolean isIpLiteral(String host) {
        return host != null && (host.indexOf(':') >= 0 || host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"));
    }

    /** 去掉 IPv6 方括号后的主机名：解析与地址策略要用它，URI 语法不允许裸 IPv6。 */
    public static String hostOf(URI uri) {
        String host = uri.getHost();
        return host != null && host.startsWith("[") && host.endsWith("]")
            ? host.substring(1, host.length() - 1)
            : host;
    }

    /** 精确主机白名单：无通配符、无后缀匹配；字面 IP 永远无法命中白名单。 */
    public static void requireAllowedHost(HeronNewsProperties properties, URI uri) {
        String host = uri.getHost();
        if (isIpLiteral(host)) throw new HeronBusinessException(400, "news_source_host", "来源地址必须使用已允许的域名");
        if (properties.getAllowedHosts().stream().noneMatch(a -> a != null && matchesEntry(a, host))) {
            throw new HeronBusinessException(400, "news_source_host",
                properties.getAllowedHosts().isEmpty() ? "尚未配置允许来源" : "来源域名不在允许清单内: " + host);
        }
    }

    /** 通配符这类非法条目按字面比较：它匹配不到任何真实主机，但也不该伪装成地址语法错误。 */
    private static boolean matchesEntry(String entry, String host) {
        try {
            return ascii(entry).equalsIgnoreCase(host);
        } catch (RuntimeException e) {
            return entry.equalsIgnoreCase(host);
        }
    }

    /** 去重用的规范链接：只剔除跟踪参数与 fragment，保留 path 与有语义 query。 */
    public static String canonical(String url) {
        if (url == null || url.isBlank()) return null;
        URI uri;
        try {
            uri = normalize(url);
        } catch (RuntimeException e) {
            return null;
        }
        StringBuilder query = new StringBuilder();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                if (pair.isBlank()) continue;
                String key = pair.indexOf('=') < 0 ? pair : pair.substring(0, pair.indexOf('='));
                if (key.toLowerCase(Locale.ROOT).startsWith("utm_") || key.equalsIgnoreCase("fbclid")
                    || key.equalsIgnoreCase("gclid")) continue;
                query.append(query.isEmpty() ? "" : "&").append(pair);
            }
        }
        return uri.getScheme() + "://" + uri.getHost() + uri.getRawPath()
            + (query.isEmpty() ? "" : "?" + query);
    }

    private static String ascii(String host) {
        try {
            return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw url("来源域名不合法: " + host);
        }
    }

    private static HeronBusinessException url(String message) {
        return new HeronBusinessException(400, "news_source_url", message);
    }
}

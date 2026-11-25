package org.dromara.heron.domain;

import java.util.Set;

/** 素材生产许可白名单；未知、空白和自定义许可一律不放行。 */
public final class MediaAssetLicensePolicy {

    public static final String UNKNOWN = "UNKNOWN";
    public static final Set<String> ALLOWED = Set.of("OWNED", "PUBLIC_DOMAIN", "CC0", "CC_BY", "CC_BY_SA");

    private MediaAssetLicensePolicy() { }

    /**
     * 判断许可是否显式属于生产白名单。
     *
     * @param licenseCode 素材登记或许可证原文
     * @return 只有精确匹配白名单时返回 true
     */
    public static boolean requiresAttribution(String licenseCode) {
        String normalized = licenseCode == null ? "" : licenseCode.trim().toUpperCase(java.util.Locale.ROOT);
        return "CC_BY".equals(normalized) || "CC_BY_SA".equals(normalized);
    }

    public static boolean isAllowed(String licenseCode) {
        return licenseCode != null && ALLOWED.contains(licenseCode.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * 规范化许可代码；无法识别时统一返回 UNKNOWN，交由调用方拒绝。
     *
     * @param licenseCode 待规范化代码
     * @return 大写代码或 UNKNOWN
     */
    public static String normalize(String licenseCode) {
        if (licenseCode == null || licenseCode.isBlank()) {
            return UNKNOWN;
        }
        String normalized = licenseCode.trim().toUpperCase(java.util.Locale.ROOT);
        return ALLOWED.contains(normalized) ? normalized : UNKNOWN;
    }
}

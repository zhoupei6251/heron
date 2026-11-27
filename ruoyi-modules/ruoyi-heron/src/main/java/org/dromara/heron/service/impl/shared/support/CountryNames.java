package org.dromara.heron.service.impl.shared.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** ISO3 → 中文国名；只覆盖主权国家，未收录的回退 OWID 英文名，不猜。 */
public final class CountryNames {

    private static final Map<String, String> ZH = load();

    private CountryNames() { }

    /**
     * 按 ISO3 取中文国名。
     *
     * @param iso3     ISO3 国家码；null 或未收录时走 fallback
     * @param fallback 未收录时的回退名（通常是 OWID 的英文名），不猜译名
     * @return 中文国名或 fallback
     */
    public static String of(String iso3, String fallback) {
        return ZH.getOrDefault(iso3, fallback);
    }

    private static Map<String, String> load() {
        try (InputStream stream = CountryNames.class.getResourceAsStream("/data/country-zh.tsv")) {
            if (stream == null) return Map.of();
            Map<String, String> map = new HashMap<>();
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("iso3")) continue;
                String[] parts = line.split("\t");
                if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                    map.put(parts[0], parts[1]);
                }
            }
            return Map.copyOf(map);
        } catch (IOException e) {
            return Map.of();
        }
    }
}

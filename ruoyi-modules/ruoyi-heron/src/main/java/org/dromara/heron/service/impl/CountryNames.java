package org.dromara.heron.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** ISO3 → 中文国名；只覆盖主权国家，未收录的回退 OWID 英文名，不猜。 */
final class CountryNames {

    private static final Map<String, String> ZH = load();

    private CountryNames() { }

    static String of(String iso3, String fallback) {
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

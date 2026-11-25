package org.dromara.heron.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("素材许可：只有显式白名单能进入生产")
class MediaAssetLicensePolicyTest {

    @Test
    void acceptsOnlyExplicitProductionLicenses() {
        for (String license : new String[] {"OWNED", "PUBLIC_DOMAIN", "CC0", "CC_BY", "CC_BY_SA"}) {
            assertTrue(MediaAssetLicensePolicy.isAllowed(license), license);
        }
        for (String license : new String[] {null, "", " ", "UNKNOWN", "RESTRICTED", "CC_BY_NC"}) {
            assertFalse(MediaAssetLicensePolicy.isAllowed(license), String.valueOf(license));
        }
        assertTrue(MediaAssetLicensePolicy.isAllowed(" cc_by "));
    }

    @Test
    void onlyCcByLicensesRequireAttribution() {
        assertTrue(MediaAssetLicensePolicy.requiresAttribution("CC_BY"));
        assertTrue(MediaAssetLicensePolicy.requiresAttribution(" cc_by_sa "));
        assertFalse(MediaAssetLicensePolicy.requiresAttribution("CC0"));
        assertFalse(MediaAssetLicensePolicy.requiresAttribution("UNKNOWN"));
    }

    @Test
    void normalizesAllowedCodesWithoutGuessingUnknownOnes() {
        assertEquals("CC_BY", MediaAssetLicensePolicy.normalize(" cc_by "));
        assertEquals("UNKNOWN", MediaAssetLicensePolicy.normalize(null));
        assertEquals("UNKNOWN", MediaAssetLicensePolicy.normalize("custom"));
    }
}

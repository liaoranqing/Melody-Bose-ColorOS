package com.tosasitill.bosemelody;

import java.util.Locale;

/** Fixed hardware/protocol facts for the Bose QC Earbuds Ultra 2 (edith). */
final class BoseDeviceConfig {
    static final String MAC = "68:F2:1F:3D:41:D7";
    static final String NAME = "Bose QuietComfort Ultra Earbuds (2nd Gen)";
    static final String BMAP_SERVICE_UUID = "00000000-deca-fade-deca-deafdecacaff";
    static final int RFCOMM_CHANNEL = 2;
    static final int PRODUCT_ID = 0x4062;

    static final int MODE_QUIET = 0;
    static final int MODE_AWARE = 1;
    static final int MODE_IMMERSION = 2;
    static final int MODE_CINEMA = 3;
    /** UI-only sentinel; it maps to Quiet mode with ANC disabled in [31.10]. */
    static final int MODE_OFF = 4;

    private BoseDeviceConfig() {
    }

    static boolean isMac(String address) {
        return address != null && MAC.equalsIgnoreCase(address);
    }

    static String normalize(String address) {
        return address == null ? "" : address.toUpperCase(Locale.ROOT);
    }
}

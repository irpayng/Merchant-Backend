package com.tms.report.core.util;

import java.util.Map;

/**
 * Maps lowercase provider codes (as stored in
 * {@code provider_balances.provider_code}) to human-friendly display names used
 * across the admin UI. Falls back to a capitalized version of the code if it
 * isn't in the table.
 */
public final class ProviderNames {

    private static final Map<String, String> NAMES = Map.ofEntries(Map.entry("vtpass", "VTPass"),
            Map.entry("wema", "Wema Bank"), Map.entry("9psb", "9PSB"), Map.entry("nibss", "NIBSS"),
            Map.entry("interswitch", "Interswitch"), Map.entry("paystack", "Paystack"),
            Map.entry("globus", "Globus Bank"), Map.entry("upsl", "UPSL"), Map.entry("termii", "Termii"),
            Map.entry("youverify", "Youverify"), Map.entry("nip", "NIP"));

    private ProviderNames() {
    }

    /**
     * Returns the display name for a provider code, or {@code null} if the code is
     * null.
     */
    public static String format(String code) {
        if (code == null)
            return null;
        String known = NAMES.get(code.toLowerCase());
        if (known != null)
            return known;
        if (code.isEmpty())
            return code;
        return code.substring(0, 1).toUpperCase() + code.substring(1);
    }
}

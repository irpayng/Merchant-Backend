package com.tms.report.modules.status;

import java.util.List;
import java.util.Map;

/**
 * Static utility for status code to name mapping. Replaces the old
 * StatusRepository that queried the now-removed `statuses` table. Status is now
 * stored as a string code directly on the transactions table.
 */
public final class StatusUtil {

    private StatusUtil() {
    }

    private static final Map<String, String> STATUS_NAMES = Map.of("completed", "Completed", "failed", "Failed",
            "processing", "Processing", "pending", "Pending", "reversed", "Reversed", "success", "Success", "rejected",
            "Rejected", "awaiting_proof", "Awaiting Proof");

    /**
     * Get display name for a status code. Falls back to capitalizing the code.
     */
    public static String getStatusName(String code) {
        if (code == null)
            return null;
        return STATUS_NAMES.getOrDefault(code, capitalize(code));
    }

    /**
     * Build a status map (matching old API shape) from a code string.
     */
    public static Map<String, Object> toStatusMap(String code) {
        if (code == null)
            return null;
        return Map.of("name", getStatusName(code), "code", code);
    }

    /**
     * Get transaction status options for filter dropdowns.
     */
    public static List<Map<String, Object>> getTransactionStatuses() {
        return List.of("completed", "processing", "failed", "reversed").stream()
                .map(code -> Map.<String, Object>of("id", code, "name", getStatusName(code), "code", code)).toList();
    }

    /**
     * Get BVN/KYC status options for filter dropdowns.
     */
    public static List<Map<String, Object>> getKycStatuses() {
        return List.of("completed", "processing", "rejected").stream()
                .map(code -> Map.<String, Object>of("id", code, "name", getStatusName(code), "code", code)).toList();
    }

    /**
     * Address-verification status options. Adds {@code awaiting_proof} on top of
     * the standard KYC set so reviewers can find records where the applicant
     * submitted an address but never uploaded a proof document — these are
     * intentionally kept out of the reviewable ("processing") queue.
     */
    public static List<Map<String, Object>> getAddressStatuses() {
        return List.of("completed", "processing", "awaiting_proof", "rejected").stream()
                .map(code -> Map.<String, Object>of("id", code, "name", getStatusName(code), "code", code)).toList();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }
}

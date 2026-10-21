package com.tms.report.modules.audit.util;

/**
 * Enum representing entity types that can be referenced in audit log
 * descriptions. Each type has singular and plural display names for consistent
 * grammar in audit messages.
 */
public enum EntityType {
    BRANCH("branch", "branches"), TERMINAL("terminal", "terminals"), TID("TID", "tids"), SETTLEMENT_ACCOUNT(
            "settlement account", "settlement-accounts"), MERCHANT_USER("user",
                    "merchant-users"), ROLE("role", "roles"), UNKNOWN("entity", "unknown");

    private final String singular;
    private final String plural;

    EntityType(String singular, String plural) {
        this.singular = singular;
        this.plural = plural;
    }

    /**
     * Returns the singular display name for this entity type. Used in audit
     * descriptions like "branch 'Lagos Main'".
     */
    public String getSingular() {
        return singular;
    }

    /**
     * Returns the plural display name for this entity type. Used for matching URL
     * path segments like "/branches/123".
     */
    public String getPlural() {
        return plural;
    }
}

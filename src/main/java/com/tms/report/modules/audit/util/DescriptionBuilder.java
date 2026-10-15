package com.tms.report.modules.audit.util;

import com.tms.report.modules.audit.model.SettlementAccountInfo;

/**
 * Utility class for building human-readable audit log descriptions. Provides
 * methods for generating contextual descriptions for nested resource operations
 * with consistent grammar and formatting.
 */
public final class DescriptionBuilder {

    private DescriptionBuilder() {
        // Utility class, prevent instantiation
    }

    /**
     * Builds a description for nested resource operations (assignments and
     * removals).
     *
     * Uses "to" for POST (assignments): "Assign TID 2001234 to branch 'Lagos Main'"
     * Uses "from" for DELETE (removals): "Remove TID 2001234 from branch 'Lagos
     * Main'"
     *
     * @param method
     *            HTTP method (POST for assign, DELETE for remove)
     * @param parentName
     *            Display name of the parent entity (e.g., "Lagos Main")
     * @param parentType
     *            Type of the parent entity (e.g., BRANCH)
     * @param targetName
     *            Display name/identifier of the target entity (e.g., "2001234")
     * @param targetType
     *            Type of the target entity (e.g., TID)
     * @return Human-readable description for the audit log
     */
    public static String buildNestedDescription(String method, String parentName, EntityType parentType,
            String targetName, EntityType targetType) {
        String action;
        String preposition;

        if ("POST".equalsIgnoreCase(method)) {
            action = "Assign";
            preposition = "to";
        } else if ("DELETE".equalsIgnoreCase(method)) {
            action = "Remove";
            preposition = "from";
        } else {
            // Fallback for other methods (PUT/PATCH)
            action = "Update";
            preposition = "on";
        }

        String targetSingular = targetType.getSingular();
        String parentSingular = parentType.getSingular();

        // Format target name: TID doesn't get quotes, other entities do
        String formattedTargetName = formatTargetName(targetName, targetType);

        // Format parent name with quotes for readability
        String formattedParentName = formatParentName(parentName, parentType);

        return String.format("%s %s %s %s %s %s", action, targetSingular, formattedTargetName, preposition,
                parentSingular, formattedParentName);
    }

    /**
     * Formats the target name based on entity type. TID values are displayed
     * without quotes, other entities use quotes.
     *
     * @param name
     *            The entity name/identifier
     * @param entityType
     *            The type of entity
     * @return Formatted name string
     */
    private static String formatTargetName(String name, EntityType entityType) {
        if (name == null || name.isEmpty()) {
            return "";
        }

        // TID values are displayed as-is without quotes
        if (entityType == EntityType.TID) {
            return name;
        }

        // Other entities get quotes for readability
        return "'" + name + "'";
    }

    /**
     * Formats the parent name with quotes for readability.
     *
     * @param name
     *            The parent entity name
     * @param entityType
     *            The type of parent entity
     * @return Formatted name string with quotes
     */
    private static String formatParentName(String name, EntityType entityType) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        return "'" + name + "'";
    }

    /**
     * Builds a description for root resource operations (create, update, delete).
     *
     * Uses "Create" for POST: "Create branch 'Lagos Main'" Uses "Update" for
     * PUT/PATCH: "Update branch 'Lagos Main'" Uses "Delete" for DELETE: "Delete
     * branch 'Lagos Main'"
     *
     * @param method
     *            HTTP method (POST, PUT, PATCH, DELETE)
     * @param entityName
     *            Display name of the entity (e.g., "Lagos Main")
     * @param entityType
     *            Type of the entity (e.g., BRANCH)
     * @return Human-readable description for the audit log
     */
    public static String buildRootDescription(String method, String entityName, EntityType entityType) {
        String action;

        if ("POST".equalsIgnoreCase(method)) {
            action = "Create";
        } else if ("PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            action = "Update";
        } else if ("DELETE".equalsIgnoreCase(method)) {
            action = "Delete";
        } else {
            // Fallback for unexpected methods
            action = "Modify";
        }

        String entitySingular = entityType.getSingular();

        // Format entity name with quotes for readability
        String formattedName = formatEntityName(entityName);

        return String.format("%s %s %s", action, entitySingular, formattedName);
    }

    /**
     * Formats the entity name with quotes for readability in root resource
     * descriptions.
     *
     * @param name
     *            The entity name
     * @return Formatted name string with quotes
     */
    private static String formatEntityName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        return "'" + name + "'";
    }

    /**
     * Builds a description for settlement account request operations. Includes both
     * the current and new account details when a current account exists.
     *
     * Format when current account exists: "Request settlement account change from
     * '{current_bank_name} ****{current_last_4}' to '{new_bank_name}
     * ****{new_last_4} ({new_account_name})'"
     *
     * Format when no current account: "Request settlement account '{new_bank_name}
     * ****{new_last_4} ({new_account_name})'"
     *
     * @param currentAccount
     *            the current default settlement account (may be null if none
     *            exists)
     * @param newAccount
     *            the requested new settlement account details
     * @return Human-readable description for the audit log
     */
    public static String buildSettlementRequestDescription(SettlementAccountInfo currentAccount,
            SettlementAccountInfo newAccount) {
        if (newAccount == null) {
            return "Request settlement account change";
        }

        String newAccountFormatted = formatSettlementAccount(newAccount, true);

        if (currentAccount != null) {
            String currentAccountFormatted = formatSettlementAccount(currentAccount, false);
            return String.format("Request settlement account change from '%s' to '%s'", currentAccountFormatted,
                    newAccountFormatted);
        }

        return String.format("Request settlement account '%s'", newAccountFormatted);
    }

    /**
     * Formats a settlement account for display in descriptions.
     *
     * @param account
     *            the settlement account info
     * @param includeAccountName
     *            whether to include the account name in parentheses
     * @return formatted string like "GTBank ****1234" or "GTBank ****1234 (John
     *         Doe)"
     */
    private static String formatSettlementAccount(SettlementAccountInfo account, boolean includeAccountName) {
        String base = String.format("%s %s", account.bankName(), account.maskedAccountNumber());

        if (includeAccountName && account.accountName() != null && !account.accountName().isEmpty()) {
            return String.format("%s (%s)", base, account.accountName());
        }

        return base;
    }

    /**
     * Builds a fallback description for nested resource operations when entity
     * names cannot be resolved. Uses ID format instead of names.
     *
     * Uses "to" for POST (assignments): "Assign TID #123 to branch #456" Uses
     * "from" for DELETE (removals): "Remove TID #123 from branch #456"
     *
     * @param method
     *            HTTP method (POST for assign, DELETE for remove)
     * @param parentId
     *            ID of the parent entity
     * @param parentType
     *            Type of the parent entity (e.g., BRANCH)
     * @param targetId
     *            ID of the target entity
     * @param targetType
     *            Type of the target entity (e.g., TID)
     * @return Human-readable fallback description for the audit log
     */
    public static String buildFallbackDescription(String method, Long parentId, EntityType parentType, Long targetId,
            EntityType targetType) {
        String action;
        String preposition;

        if ("POST".equalsIgnoreCase(method)) {
            action = "Assign";
            preposition = "to";
        } else if ("DELETE".equalsIgnoreCase(method)) {
            action = "Remove";
            preposition = "from";
        } else {
            // Fallback for other methods (PUT/PATCH)
            action = "Update";
            preposition = "on";
        }

        String targetSingular = targetType.getSingular();
        String parentSingular = parentType.getSingular();

        // Format IDs with # prefix for fallback display
        String formattedTargetId = targetId != null ? "#" + targetId : "";
        String formattedParentId = parentId != null ? "#" + parentId : "";

        return String.format("%s %s %s %s %s %s", action, targetSingular, formattedTargetId, preposition,
                parentSingular, formattedParentId);
    }
}

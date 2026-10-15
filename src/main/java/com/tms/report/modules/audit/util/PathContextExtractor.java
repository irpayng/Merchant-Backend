package com.tms.report.modules.audit.util;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts entity context from URL paths for audit logging. Handles both nested
 * resource paths (e.g., /branches/{id}/tids/{tidId}) and root resource paths
 * (e.g., /branches/{id}).
 */
public final class PathContextExtractor {

    /**
     * Mapping from URL path segments (plural) to EntityType.
     */
    private static final Map<String, EntityType> PATH_SEGMENT_TO_ENTITY_TYPE = Map.of("branches", EntityType.BRANCH,
            "terminals", EntityType.TERMINAL, "tids", EntityType.TID, "settlement-accounts",
            EntityType.SETTLEMENT_ACCOUNT, "merchant-users", EntityType.MERCHANT_USER, "roles", EntityType.ROLE);

    /**
     * Pattern for nested resource paths like /branches/{id}/tids/{tidId}. Captures:
     * group(1)=parent segment, group(2)=parent ID, group(3)=target segment,
     * group(4)=target ID.
     */
    private static final Pattern NESTED_RESOURCE_PATTERN = Pattern
            .compile("/([a-z-]+)/(\\d+)/([a-z-]+)/(\\d+)(?:/.*)?$");

    /**
     * Pattern for sub-action paths like /branches/{id}/settlement-account.
     * Captures: group(1)=parent segment, group(2)=parent ID, group(3)=sub-action.
     */
    private static final Pattern SUB_ACTION_PATTERN = Pattern.compile("/([a-z-]+)/(\\d+)/([a-z-]+)(?:/)?$");

    /**
     * Pattern for root resource paths like /branches/{id}. Captures:
     * group(1)=resource segment, group(2)=resource ID.
     */
    private static final Pattern ROOT_RESOURCE_PATTERN = Pattern.compile("/([a-z-]+)/(\\d+)(?:/)?$");

    private PathContextExtractor() {
        // Utility class, prevent instantiation
    }

    /**
     * Extract nested resource context from a URL path.
     *
     * <p>
     * Handles patterns like:
     * <ul>
     * <li>{@code /branches/{id}/tids/{tidId}} → PathContext(BRANCH, branchId, TID,
     * tidId)</li>
     * <li>{@code /branches/{id}/terminals/{terminalId}} → PathContext(BRANCH,
     * branchId, TERMINAL, terminalId)</li>
     * <li>{@code /branches/{id}/settlement-account} → PathContext(BRANCH, branchId,
     * null, null, "settlement-account")</li>
     * </ul>
     *
     * @param path
     *            URL path like "/branches/123/terminals/456"
     * @return PathContext with parent and target entity info, or empty if the path
     *         cannot be parsed
     */
    public static Optional<PathContext> extractNestedContext(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        // Normalize path: remove query string and trailing slashes
        String normalizedPath = normalizePath(path);

        // Try nested resource pattern first (e.g., /branches/123/tids/456)
        Optional<PathContext> nestedResult = tryNestedResourcePattern(normalizedPath);
        if (nestedResult.isPresent()) {
            return nestedResult;
        }

        // Try sub-action pattern (e.g., /branches/123/settlement-account)
        return trySubActionPattern(normalizedPath);
    }

    /**
     * Extract root resource context from a path.
     *
     * @param path
     *            URL path like "/branches/123"
     * @return PathContext with resource type and ID, or empty if the path cannot be
     *         parsed
     */
    public static Optional<PathContext> extractRootContext(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        String normalizedPath = normalizePath(path);

        Matcher matcher = ROOT_RESOURCE_PATTERN.matcher(normalizedPath);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String resourceSegment = matcher.group(1);
        String resourceIdStr = matcher.group(2);

        EntityType entityType = PATH_SEGMENT_TO_ENTITY_TYPE.getOrDefault(resourceSegment, EntityType.UNKNOWN);
        Long resourceId = parseNumericId(resourceIdStr);

        if (resourceId == null) {
            return Optional.empty();
        }

        return Optional.of(PathContext.forRootResource(entityType, resourceId));
    }

    /**
     * Determine if path represents a nested resource operation.
     *
     * @param path
     *            the URL path to check
     * @return true if the path matches a nested resource pattern
     */
    public static boolean isNestedResourcePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }

        String normalizedPath = normalizePath(path);

        // Check for nested resource pattern (e.g., /branches/123/tids/456)
        if (NESTED_RESOURCE_PATTERN.matcher(normalizedPath).find()) {
            return true;
        }

        // Check for sub-action pattern (e.g., /branches/123/settlement-account)
        Matcher subActionMatcher = SUB_ACTION_PATTERN.matcher(normalizedPath);
        if (subActionMatcher.find()) {
            String parentSegment = subActionMatcher.group(1);
            String subAction = subActionMatcher.group(3);
            // It's a nested path if the parent is a known entity and the sub-action is NOT
            // a known entity with an ID
            return PATH_SEGMENT_TO_ENTITY_TYPE.containsKey(parentSegment)
                    && !PATH_SEGMENT_TO_ENTITY_TYPE.containsKey(subAction);
        }

        return false;
    }

    /**
     * Map a URL path segment to its corresponding EntityType.
     *
     * @param pathSegment
     *            the plural path segment (e.g., "branches", "tids")
     * @return the corresponding EntityType, or UNKNOWN if not mapped
     */
    public static EntityType mapPathSegmentToEntityType(String pathSegment) {
        return PATH_SEGMENT_TO_ENTITY_TYPE.getOrDefault(pathSegment, EntityType.UNKNOWN);
    }

    /**
     * Normalize a URL path by removing query strings and extra slashes.
     */
    private static String normalizePath(String path) {
        // Remove query string if present
        int queryIndex = path.indexOf('?');
        String result = queryIndex >= 0 ? path.substring(0, queryIndex) : path;

        // Remove trailing slash (but keep leading slash)
        if (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }

    /**
     * Try to match and extract a nested resource pattern.
     */
    private static Optional<PathContext> tryNestedResourcePattern(String path) {
        Matcher matcher = NESTED_RESOURCE_PATTERN.matcher(path);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String parentSegment = matcher.group(1);
        String parentIdStr = matcher.group(2);
        String targetSegment = matcher.group(3);
        String targetIdStr = matcher.group(4);

        EntityType parentType = PATH_SEGMENT_TO_ENTITY_TYPE.getOrDefault(parentSegment, EntityType.UNKNOWN);
        EntityType targetType = PATH_SEGMENT_TO_ENTITY_TYPE.getOrDefault(targetSegment, EntityType.UNKNOWN);

        Long parentId = parseNumericId(parentIdStr);
        Long targetId = parseNumericId(targetIdStr);

        if (parentId == null || targetId == null) {
            return Optional.empty();
        }

        return Optional.of(PathContext.forNestedResource(parentType, parentId, targetType, targetId));
    }

    /**
     * Try to match and extract a sub-action pattern.
     */
    private static Optional<PathContext> trySubActionPattern(String path) {
        Matcher matcher = SUB_ACTION_PATTERN.matcher(path);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String parentSegment = matcher.group(1);
        String parentIdStr = matcher.group(2);
        String subActionSegment = matcher.group(3);

        // Only treat as sub-action if the third segment is NOT a known entity type
        // (which would mean it's a nested resource without an ID, not a sub-action)
        if (PATH_SEGMENT_TO_ENTITY_TYPE.containsKey(subActionSegment)) {
            // This is likely an incomplete nested resource path (e.g., /branches/123/tids)
            // Not a sub-action
            return Optional.empty();
        }

        EntityType parentType = PATH_SEGMENT_TO_ENTITY_TYPE.getOrDefault(parentSegment, EntityType.UNKNOWN);
        Long parentId = parseNumericId(parentIdStr);

        if (parentId == null) {
            return Optional.empty();
        }

        return Optional.of(PathContext.forSubAction(parentType, parentId, subActionSegment));
    }

    /**
     * Parse a string as a numeric ID.
     *
     * @param idStr
     *            the string to parse
     * @return the parsed Long, or null if parsing fails
     */
    private static Long parseNumericId(String idStr) {
        if (idStr == null || idStr.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

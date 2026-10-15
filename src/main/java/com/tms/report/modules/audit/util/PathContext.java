package com.tms.report.modules.audit.util;

/**
 * Represents the parsed context from a URL path for audit logging. Captures
 * both parent and target entity information for nested resource operations.
 *
 * @param parentType
 *            The type of the parent entity (e.g., BRANCH in
 *            /branches/{id}/tids/{tidId})
 * @param parentId
 *            The ID of the parent entity
 * @param targetType
 *            The type of the target entity being acted upon (e.g., TID)
 * @param targetId
 *            The ID of the target entity
 * @param subAction
 *            Optional sub-action identifier (e.g., "settlement-account",
 *            "lock", "unlock")
 */
public record PathContext(EntityType parentType, Long parentId, EntityType targetType, Long targetId,
        String subAction) {

    /**
     * Creates a PathContext for a root resource operation (no parent entity).
     *
     * @param targetType
     *            The type of the entity being acted upon
     * @param targetId
     *            The ID of the entity
     * @return PathContext with null parent fields
     */
    public static PathContext forRootResource(EntityType targetType, Long targetId) {
        return new PathContext(null, null, targetType, targetId, null);
    }

    /**
     * Creates a PathContext for a nested resource operation.
     *
     * @param parentType
     *            The type of the parent entity
     * @param parentId
     *            The ID of the parent entity
     * @param targetType
     *            The type of the target entity
     * @param targetId
     *            The ID of the target entity
     * @return PathContext with both parent and target information
     */
    public static PathContext forNestedResource(EntityType parentType, Long parentId, EntityType targetType,
            Long targetId) {
        return new PathContext(parentType, parentId, targetType, targetId, null);
    }

    /**
     * Creates a PathContext for a sub-action on a resource (e.g., lock/unlock).
     *
     * @param parentType
     *            The type of the parent entity
     * @param parentId
     *            The ID of the parent entity
     * @param subAction
     *            The sub-action identifier
     * @return PathContext with sub-action information
     */
    public static PathContext forSubAction(EntityType parentType, Long parentId, String subAction) {
        return new PathContext(parentType, parentId, null, null, subAction);
    }

    /**
     * Returns true if this context represents a nested resource operation.
     */
    public boolean isNestedResource() {
        return parentType != null && parentId != null && targetType != null && targetId != null;
    }

    /**
     * Returns true if this context represents a sub-action (like lock/unlock).
     */
    public boolean isSubAction() {
        return subAction != null && !subAction.isEmpty();
    }

    /**
     * Returns true if this context represents a root resource operation.
     */
    public boolean isRootResource() {
        return parentType == null && parentId == null && targetType != null;
    }
}

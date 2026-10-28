package com.tms.report.modules.session.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Event published by tms-user when dashboard sessions should be revoked. This
 * covers security-sensitive operations like:
 * <ul>
 * <li>Password change / reset</li>
 * <li>User blocked / suspended</li>
 * <li>User-initiated logout-all-devices</li>
 * <li>Admin-initiated session invalidation</li>
 * </ul>
 *
 * <p>
 * The event is keyed by tmsUserId so all revocations for a user go to the same
 * partition (ordering guarantee).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionRevocationEvent {

    /**
     * The tms-user ID (users.id for owners, operators.id for staff).
     */
    private Long tmsUserId;

    /**
     * "user" for merchant owners, "operator" for staff.
     */
    private String userType;

    /**
     * Reason for revocation (for audit logs).
     */
    private String reason;

    /**
     * Timestamp when the revocation was triggered (ISO-8601).
     */
    private String triggeredAt;

    /**
     * Optional: the source that triggered the revocation.
     */
    private String source;
}

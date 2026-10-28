package com.tms.report.modules.profile;

import lombok.Builder;
import lombok.Data;

/**
 * User profile data fetched from tms-user. This is the source of truth for user
 * identity information — merchant_users should NOT duplicate these fields.
 */
@Data
@Builder
public class UserProfile {

    /** The user's id in tms-user (users.id). */
    private Long userId;

    /** User's email address (may be null for phone-only signups). */
    private String email;

    /** User's phone number (may be null for email-only signups). */
    private String phoneNumber;

    /** User's first name. */
    private String firstName;

    /** User's last name. */
    private String lastName;

    /** Business/merchant name. */
    private String businessName;

    /** User type: user, merchant, agent, aggregator, etc. */
    private String type;

    /**
     * Build display name from available fields. Priority: businessName →
     * firstName+lastName → email → phoneNumber.
     */
    public String getDisplayName() {
        if (businessName != null && !businessName.isBlank()) {
            return businessName;
        }
        if (firstName != null && !firstName.isBlank()) {
            if (lastName != null && !lastName.isBlank()) {
                return firstName + " " + lastName;
            }
            return firstName;
        }
        if (lastName != null && !lastName.isBlank()) {
            return lastName;
        }
        if (email != null && !email.isBlank()) {
            return email;
        }
        return phoneNumber;
    }

    /**
     * Get the primary contact identifier (email preferred, phone as fallback).
     */
    public String getPrimaryIdentifier() {
        if (email != null && !email.isBlank()) {
            return email;
        }
        return phoneNumber;
    }
}

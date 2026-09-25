package com.tms.report.modules.deviceactivity.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Maps to the replicated activities table from audit-service. Device activities
 * are identified by {@code actionable_type = 'device_login'}. The
 * {@code reference} field stores the device serial, and {@code actionable_id}
 * stores the operator ID (null for direct merchant logins).
 */
@Entity
@Table(name = "activities", indexes = {@Index(name = "idx_activities_user_id", columnList = "user_id"),
        @Index(name = "idx_activities_actionable", columnList = "actionable_type, actionable_id"),
        @Index(name = "idx_activities_reference", columnList = "reference"),
        @Index(name = "idx_activities_created_at", columnList = "created_at")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The merchant (users.id) who owns the terminal. */
    @Column(name = "user_id", nullable = false)
    @JsonProperty("merchant_id")
    private Long userId;

    /**
     * Morph type — 'device_login' for device activities. Other types (transactions,
     * users) are filtered out.
     */
    @Column(name = "actionable_type")
    @JsonProperty("actionable_type")
    private String actionableType;

    /**
     * Operator ID for operator logins, null for direct merchant logins.
     */
    @Column(name = "actionable_id")
    @JsonProperty("operator_id")
    private Long actionableId;

    /** The action: 'login' or 'logout'. */
    @Column(nullable = false)
    private String action;

    /** Human-readable description of the activity. */
    @Column(nullable = false)
    private String description;

    /**
     * Device serial number — stored in reference field for device activities.
     */
    @Column(length = 100)
    @JsonProperty("device_serial")
    private String reference;

    @Column(name = "created_at")
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    // ── Transient fields for API response ───────────────────

    /**
     * Terminal info for display. Populated by the service when listing/showing
     * activities.
     */
    @Transient
    private TerminalInfo terminal;

    /**
     * Operator name extracted from description or looked up.
     */
    @Transient
    @JsonProperty("operator_name")
    private String operatorName;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TerminalInfo {
        private Long id;
        private String serial;
        private String model;
        private String make;
    }
}

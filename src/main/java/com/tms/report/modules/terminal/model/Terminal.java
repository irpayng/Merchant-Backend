package com.tms.report.modules.terminal.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "terminals")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Terminal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    @JsonProperty("user_id")
    private Long userId;

    private String serial;
    private String os;
    private String model;
    private String make;

    @Column(name = "created_at")
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    @JsonIgnore
    @Column(name = "secret_key", columnDefinition = "text")
    private String secretKey;

    private Boolean active;

    /** Admin-controlled hard lock — see config service TerminalEntity. */
    private Boolean locked;

    @Column(name = "lock_message")
    @JsonProperty("lock_message")
    private String lockMessage;

    @Column(name = "locked_at")
    @JsonProperty("locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_by")
    @JsonProperty("locked_by")
    private String lockedBy;

    /**
     * The agent this terminal is mapped to, resolved from {@code user_id} against
     * the replicated {@code users}/{@code profiles} tables. Not a DB column —
     * populated by the controller when listing/showing terminals so the UI can
     * render the agent's name and email without an extra lookup. {@code null} when
     * the terminal is unmapped.
     */
    @Transient
    private MappedUser user;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MappedUser {
        private Long id;
        private String name;
        private String email;
    }
}

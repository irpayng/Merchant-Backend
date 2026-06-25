package com.tms.report.modules.terminal.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only entity mapped to provider active_terminals tables replicated via
 * logical replication. Each provider has its own table: nibss_active_terminals,
 * interswitch_active_terminals, etc.
 */
@Entity
@Table(name = "nibss_active_terminals")
@Data
@NoArgsConstructor
public class ProviderKeyStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "terminal_id")
    private String terminalId;

    @Column(name = "key_status")
    private String keyStatus;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "keys_downloaded_at")
    private LocalDateTime keysDownloadedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}

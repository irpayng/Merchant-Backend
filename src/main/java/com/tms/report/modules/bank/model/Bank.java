package com.tms.report.modules.bank.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An enrolled tenant bank for the super-merchant portal. The {@code code} is a
 * NIBSS institution code (validated against the replicated {@code bank_codes}
 * reference at enrollment) and is the tenant key that portal users and TIDs are
 * scoped on. Owned by super-merchant (supermerchant schema).
 */
@Entity
@Table(name = "banks", schema = "supermerchant")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Bank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String code;

    private String name;

    @Column(name = "contact_email")
    @JsonProperty("contact_email")
    private String contactEmail;

    @Builder.Default
    private String status = "active";

    @CreationTimestamp
    @Column(name = "created_at")
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
}

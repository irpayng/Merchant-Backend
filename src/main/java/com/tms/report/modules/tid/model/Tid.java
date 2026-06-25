package com.tms.report.modules.tid.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tids")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    @JsonProperty("user_id")
    private Long userId;

    @Builder.Default
    private Boolean internal = false;

    /**
     * Processor scope for an internal TID — a comma/space separated list of
     * provider codes (e.g. "nibss,upsl" or "interswitch"). Blank/null means the
     * internal TID is unscoped and usable by any processor.
     */
    private String processor;

    @Column(name = "terminal_id")
    @JsonProperty("terminal_id")
    private String terminalId;

    @Column(name = "merchant_id")
    @JsonProperty("merchant_id")
    private String merchantId;

    @Column(name = "merchant_name")
    @JsonProperty("merchant_name")
    private String merchantName;

    @Column(name = "bank_acc_no")
    @JsonProperty("bank_acc_no")
    private String bankAccNo;

    @Column(name = "merchant_category_code")
    @JsonProperty("merchant_category_code")
    private String merchantCategoryCode;

    @Column(name = "state_code")
    @JsonProperty("state_code")
    private String stateCode;

    @Column(name = "merchant_physical_addr")
    @JsonProperty("merchant_physical_addr")
    private String merchantPhysicalAddr;

    @Column(name = "merchant_address_lga_code")
    @JsonProperty("merchant_address_lga_code")
    private String merchantAddressLgaCode;

    private String email;

    @Column(name = "created_at")
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
}

package com.tms.report.modules.transaction.model;

import com.tms.report.modules.user.model.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 26)
    private String reference;

    @Column(name = "user_id", insertable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_code")
    private String productCode;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "provider_code")
    private String providerCode;

    @Column(name = "channel")
    private String channel;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "status_code")
    private String statusCode;

    @Column(name = "status_message")
    private String statusMessage;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "service_fee", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal serviceFee = BigDecimal.ZERO;

    @Column(name = "agent_commission", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal agentCommission = BigDecimal.ZERO;

    @Column(name = "aggregator_commission", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal aggregatorCommission = BigDecimal.ZERO;

    @Column(name = "super_aggregator_commission", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal superAggregatorCommission = BigDecimal.ZERO;

    @Column(name = "company_commission", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal companyCommission = BigDecimal.ZERO;

    @Column(name = "amount_to_pay", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal amountToPay = BigDecimal.ZERO;

    @Column(name = "terminal_id")
    private String terminalId;

    private BigDecimal latitude;
    private BigDecimal longitude;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

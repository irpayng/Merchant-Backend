package com.tms.report.modules.transaction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class TransactionDto {

    private Long id;
    private String reference;

    private UserRef user;
    private NameCodeRef product;
    private NameCodeRef provider;
    private NameCodeRef channel;

    @JsonProperty("payment_method")
    private NameCodeRef paymentMethod;

    private String amount;
    private StatusRef status;

    @JsonProperty("service_fee")
    private String serviceFee;

    @JsonProperty("agent_commission")
    private String agentCommission;

    @JsonProperty("aggregator_commission")
    private String aggregatorCommission;

    @JsonProperty("super_aggregator_commission")
    private String superAggregatorCommission;

    @JsonProperty("company_commission")
    private String companyCommission;

    @JsonProperty("provider_cost")
    private String providerCost;

    @JsonProperty("amount_to_pay")
    private String amountToPay;

    /**
     * Whether this transaction can be reversed from the admin UI — true only for a
     * FAILED card transaction that still has a stored reversal template (i.e. the
     * auth was transmitted to the switch and may have debited the customer) and has
     * not already been reversed. Successful transactions are never reversible: the
     * agent already received value and gave cash to the customer. Drives the
     * visibility of the "Reverse" row action.
     */
    @JsonProperty("reversible")
    private Boolean reversible;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}

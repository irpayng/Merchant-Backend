package com.tms.report.modules.user.dto;

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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserDto {

    private Long id;
    private String name;
    private String email;
    private Object avatar;

    @JsonProperty("phone_number")
    private String phoneNumber;

    private TierDto tier;
    private String type;

    @JsonProperty("last_transaction_date")
    private LocalDateTime lastTransactionDate;

    private Boolean active;
    private Boolean pnd;

    @JsonProperty("pnd_reason")
    private String pndReason;

    private Boolean frozen;
    private Boolean suspended;
    private Boolean blocked;

    @JsonProperty("instant_settlement")
    private Boolean instantSettlement;

    private Object parent;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}

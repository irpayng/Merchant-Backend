package com.tms.report.modules.merchantuser.kafka;

import com.tms.report.modules.auth.service.AuthService;
import com.tms.report.modules.merchantuser.service.MerchantProvisioningService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Provisions the merchant dashboard owner login when kyc approves a merchant
 * business application.
 *
 * <p>
 * Consumes the same {@code outbox.event.kyc} stream the notification service
 * uses to send the "your merchant application has been approved" push, so the
 * dashboard account exists by the time the merchant acts on that message.
 * Previously nothing created it and the merchant was told they were approved
 * while the dashboard had no account for them at all.
 *
 * <p>
 * Uses a shared (not per-pod) consumer group: unlike the SSE consumers this is
 * a write side effect that must happen exactly once per event, not once per
 * replica.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantApprovalConsumer {

    private static final String MERCHANT = "merchant";
    private static final String BUSINESS_APPROVED = "business_approved";

    private final MerchantProvisioningService provisioningService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${app.kafka.kyc-topic:outbox.event.kyc}", groupId = "${app.kafka.merchant-provisioning-group:merchant-backend-merchant-provisioning}")
    public void consume(String message) {
        try {
            JsonNode event = objectMapper.readTree(extractPayload(message));

            String verificationType = text(event, "verification_type");
            String applicationType = text(event, "application_type");
            if (!BUSINESS_APPROVED.equals(verificationType) || !MERCHANT.equalsIgnoreCase(applicationType)) {
                return;
            }

            JsonNode userId = event.get("user_id");
            if (userId == null || userId.isNull()) {
                log.warn("business_approved event for a merchant carried no user_id — skipping provisioning");
                return;
            }

            long merchantId = userId.asLong();
            Optional<Long> provisioned = provisioningService.provisionOwner(merchantId);
            if (provisioned.isEmpty()) {
                return;
            }

            // Separate transaction from the row creation on purpose: a failed
            // SMS/email must not undo the login, otherwise a notification
            // outage leaves the merchant locked out again. They can resend the
            // challenge themselves from the dashboard.
            try {
                authService.issueActivationChallenge(provisioned.get());
            } catch (Exception e) {
                log.error("Provisioned merchant dashboard login {} for merchant_id={} but could not send the "
                        + "activation challenge: {}", provisioned.get(), merchantId, e.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to process kyc event for merchant provisioning: {}", e.getMessage(), e);
        }
    }

    /**
     * Unwrap the Debezium outbox envelope. The message is either {@code {"payload":
     * "<json string>"}}, {@code {"payload": {...}}} or the bare event — mirrors
     * notification-service's {@code KycCompletedConsumer}.
     */
    private String extractPayload(String rawMessage) {
        try {
            JsonNode envelope = objectMapper.readTree(rawMessage);
            JsonNode payload = envelope.get("payload");
            if (payload != null) {
                return payload.isTextual() ? payload.textValue() : payload.toString();
            }
            if (envelope.isTextual()) {
                return envelope.textValue();
            }
        } catch (Exception ignored) {
            // Fall through to the raw message.
        }
        return rawMessage;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}

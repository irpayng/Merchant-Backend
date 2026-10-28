package com.tms.report.modules.session.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tms.report.modules.session.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes session revocation events from tms-user via the outbox pattern.
 * 
 * <p>
 * When a user changes their password, gets blocked, or triggers "logout all
 * devices", tms-user writes an event to its outbox table with aggregateType
 * "session". Debezium captures these and publishes to the
 * {@code outbox.event.session} topic. This consumer revokes all matching
 * dashboard sessions.
 *
 * <p>
 * The consumer uses a fixed group ID (not per-pod) because we want each event
 * to be processed exactly once across all Merchant-Backend replicas.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRevocationConsumer {

    private static final String TOPIC = "outbox.event.session";
    private static final String GROUP_ID = "merchant-backend-session-revocation";

    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = TOPIC, groupId = GROUP_ID)
    public void consume(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);

            // Debezium outbox events may have a payload wrapper
            JsonNode payload = root.has("payload") ? root.get("payload") : root;

            String eventType = getStringField(payload, "type");
            String aggregateType = getStringField(payload, "aggregateType");

            // Only process session-related events
            if (!"session".equals(aggregateType)) {
                log.debug("Ignoring non-session event: aggregateType={}", aggregateType);
                return;
            }

            // Parse the actual event data (stored as JSON string in payload field)
            String payloadJson = getStringField(payload, "payload");
            if (payloadJson == null || payloadJson.isBlank()) {
                log.warn("Ignoring event with empty payload: type={}", eventType);
                return;
            }

            JsonNode eventData = objectMapper.readTree(payloadJson);

            if ("session.revoke_all".equals(eventType)) {
                handleRevokeAll(eventData);
            } else {
                log.debug("Ignoring unhandled session event type: {}", eventType);
            }

        } catch (Exception e) {
            // Log but don't rethrow - we don't want to block the consumer on parse errors.
            log.error("Failed to process session revocation event: {}", e.getMessage(), e);
        }
    }

    private void handleRevokeAll(JsonNode data) {
        Long tmsUserId = getLongField(data, "tms_user_id", "tmsUserId");
        String userType = getStringField(data, "user_type", "userType");
        String reason = getStringField(data, "reason");
        String source = getStringField(data, "source");

        log.info("Processing session revocation event: tmsUserId={} userType={} reason={} source={}", tmsUserId,
                userType, reason, source);

        if (tmsUserId == null || userType == null) {
            log.warn("Ignoring malformed revocation event: missing tmsUserId or userType");
            return;
        }

        int revoked = sessionService.revokeSessionsForTmsUser(tmsUserId, userType,
                reason != null ? reason : "tms-user revocation event");

        log.info("Revoked {} sessions for tmsUserId={} userType={}", revoked, tmsUserId, userType);
    }

    private String getStringField(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName) && !node.get(fieldName).isNull()) {
                return node.get(fieldName).asText();
            }
        }
        return null;
    }

    private Long getLongField(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName) && !node.get(fieldName).isNull()) {
                return node.get(fieldName).asLong();
            }
        }
        return null;
    }
}

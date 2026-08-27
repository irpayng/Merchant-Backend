package com.tms.report.modules.dispute.sse;

import com.tms.report.modules.dispute.dto.ConversationDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumer for dispute conversation events. Receives events from the
 * dispute microservice and pushes them to merchant SSE subscribers via
 * {@link DisputeSseRegistry}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DisputeConversationConsumer {

    private final DisputeSseRegistry sseRegistry;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "dispute-conversation", groupId = "#{@kafkaGroupIds.disputeSseGroup}")
    public void consume(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            ConversationDto dto = ConversationDto.from(node);
            String json = objectMapper.writeValueAsString(dto);

            // Per-dispute subscribers: the merchant user with this thread open.
            sseRegistry.broadcast(dto.disputeId(), "new_message", json);

            log.debug("Pushed conversation event to SSE: disputeId={}, senderType={}, attachment={}", dto.disputeId(),
                    dto.senderType(), dto.attachment() != null);
        } catch (Exception e) {
            log.error("Failed to process dispute-conversation event: {}", e.getMessage(), e);
        }
    }
}

package com.tms.report.modules.dispute.sse;

import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.dispute.repository.DisputeRepository;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE endpoint for real-time dispute conversation updates in the merchant
 * portal.
 *
 * GET /disputes/{id}/stream — opens an SSE connection. New messages are pushed
 * by {@link DisputeConversationConsumer} via Kafka.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DisputeSseController {

    private final DisputeRepository disputeRepository;
    private final MerchantScope merchantScope;
    private final DisputeSseRegistry sseRegistry;

    @GetMapping(value = "/disputes/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            throw new IllegalStateException("No authenticated merchant");
        }

        // Verify dispute exists and belongs to this merchant
        disputeRepository.findByIdAndUserId(id, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Dispute not found: " + id));

        SseEmitter emitter = new SseEmitter(0L); // no timeout

        sseRegistry.register(id, emitter);

        // Send connected event
        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"dispute_id\":" + id + "}"));
        } catch (IOException e) {
            log.warn("Failed to send connected event for dispute {}", id);
        }

        log.info("SSE stream opened for dispute {} by merchant {}", id, merchantId);
        return emitter;
    }
}

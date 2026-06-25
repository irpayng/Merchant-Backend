package com.tms.report.modules.transaction.sse;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE endpoint for real-time transaction updates in the admin panel.
 *
 * GET /transactions/stream — opens an SSE connection. New transaction results
 * are pushed by {@link TransactionResultConsumer} via Kafka.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TransactionSseController {

    private final TransactionSseRegistry sseRegistry;

    @GetMapping(value = "/transactions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout

        sseRegistry.register(emitter);

        // Send connected event
        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"status\":\"ok\"}"));
        } catch (IOException e) {
            log.warn("Failed to send connected event for transaction SSE");
        }

        log.info("Transaction SSE stream opened");
        return emitter;
    }
}

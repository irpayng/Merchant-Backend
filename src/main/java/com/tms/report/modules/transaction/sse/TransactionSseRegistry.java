package com.tms.report.modules.transaction.sse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Global SSE registry for real-time transaction updates. Broadcasts new
 * transaction results to all connected admin clients viewing the transactions
 * page.
 */
@Slf4j
@Component
public class TransactionSseRegistry {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public void register(SseEmitter emitter) {
        emitters.add(emitter);

        Runnable cleanup = () -> {
            emitters.remove(emitter);
            log.debug("Transaction SSE connection removed (total: {})", emitters.size());
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        log.info("Transaction SSE connection registered (total: {})", emitters.size());
    }

    /**
     * Broadcast a transaction event to all connected admin clients.
     */
    public void broadcast(String eventName, String jsonData) {
        if (emitters.isEmpty())
            return;

        List<SseEmitter> dead = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(jsonData));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }

        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
        }
    }

    /**
     * Periodic heartbeat to keep connections alive and prune dead emitters. Runs
     * every 25 seconds — below the typical 30s proxy idle timeout.
     */
    @Scheduled(fixedRate = 25000)
    public void heartbeat() {
        if (emitters.isEmpty())
            return;

        List<SseEmitter> dead = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("{}"));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }

        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
            log.debug("Transaction SSE heartbeat: pruned {} dead emitters, {} remaining", dead.size(), emitters.size());
        }
    }
}

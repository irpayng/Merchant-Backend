package com.tms.report.modules.dispute.sse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory registry of active SSE connections for dispute conversations. Keyed
 * by disputeId so all merchant users watching a dispute get updates.
 */
@Slf4j
@Component
public class DisputeSseRegistry {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public void register(Long disputeId, SseEmitter emitter) {
        emitters.computeIfAbsent(disputeId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> {
            List<SseEmitter> list = emitters.get(disputeId);
            if (list != null) {
                list.remove(emitter);
                if (list.isEmpty())
                    emitters.remove(disputeId);
            }
            log.debug("SSE connection removed for dispute {}", disputeId);
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        log.info("SSE connection registered for dispute {} (total: {})", disputeId,
                emitters.getOrDefault(disputeId, List.of()).size());
    }

    /**
     * Broadcast a new conversation event to all SSE subscribers watching a dispute.
     */
    public void broadcast(Long disputeId, String eventName, String jsonData) {
        List<SseEmitter> conns = emitters.get(disputeId);
        if (conns == null || conns.isEmpty())
            return;

        List<SseEmitter> dead = new java.util.ArrayList<>();
        for (SseEmitter emitter : conns) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(jsonData));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }

        if (!dead.isEmpty()) {
            conns.removeAll(dead);
            if (conns.isEmpty())
                emitters.remove(disputeId);
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

        int totalRemoved = 0;
        for (var entry : emitters.entrySet()) {
            List<SseEmitter> conns = entry.getValue();
            List<SseEmitter> dead = new java.util.ArrayList<>();

            for (SseEmitter emitter : conns) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("{}"));
                } catch (IOException e) {
                    dead.add(emitter);
                }
            }

            if (!dead.isEmpty()) {
                conns.removeAll(dead);
                totalRemoved += dead.size();
                if (conns.isEmpty())
                    emitters.remove(entry.getKey());
            }
        }

        if (totalRemoved > 0) {
            int totalActive = emitters.values().stream().mapToInt(List::size).sum();
            log.debug("Dispute SSE heartbeat: pruned {} dead, {} active across {} disputes", totalRemoved, totalActive,
                    emitters.size());
        }
    }
}

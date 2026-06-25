package com.tms.report.core.config;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Computes this pod's per-instance SSE consumer-group ids exactly once and
 * shares them between the {@code @KafkaListener} declarations and the
 * {@link SseConsumerGroupCleaner} shutdown hook.
 *
 * <p>
 * The SSE consumers ({@code transaction-result}, {@code dispute-conversation})
 * must use a <b>per-pod</b> group so every replica receives every message —
 * those topics are single-partition and the SSE emitters live in memory on each
 * pod, so a shared group would starve clients connected to the non-consuming
 * pods. The group id is therefore suffixed with the pod hostname (or a random
 * UUID in local dev).
 *
 * <p>
 * The downside of per-pod groups is that a retired pod leaves its group behind
 * with no members; as new messages arrive that dead group's lag grows forever,
 * producing phantom consumer-lag and group sprawl that accumulates on every
 * deploy. {@link SseConsumerGroupCleaner} deletes this pod's groups on graceful
 * shutdown to stop that — which is why the suffix must be computed once here
 * and reused, rather than re-evaluating the SpEL (a fresh UUID would not match
 * the group the listener actually joined).
 */
@Component
public class KafkaGroupIds {

    public static final String TRANSACTION_SSE_PREFIX = "tms-report-transaction-sse-";
    public static final String DISPUTE_SSE_PREFIX = "tms-report-dispute-sse-";

    private final String suffix;

    public KafkaGroupIds() {
        String host = System.getenv("HOSTNAME");
        this.suffix = (host != null && !host.isBlank()) ? host : UUID.randomUUID().toString();
    }

    public String getSuffix() {
        return suffix;
    }

    public String getTransactionSseGroup() {
        return TRANSACTION_SSE_PREFIX + suffix;
    }

    public String getDisputeSseGroup() {
        return DISPUTE_SSE_PREFIX + suffix;
    }
}

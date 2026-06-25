package com.tms.report.core.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/**
 * Deletes this pod's per-instance SSE consumer groups on graceful shutdown so a
 * retired replica does not leave dead groups behind. Without this, every deploy
 * orphans one transaction-sse and one dispute-sse group per pod; their lag then
 * grows forever as new events arrive, producing ever-climbing phantom consumer
 * lag and unbounded group sprawl on the broker.
 *
 * <p>
 * On {@link ContextClosedEvent} we first stop the Kafka listener containers so
 * this pod leaves its groups (making them empty), then ask the broker to delete
 * them. The whole thing is best-effort and time-bounded: a failure here must
 * never block or fail shutdown, and an ungraceful kill (SIGKILL/OOM) simply
 * leaves the group to age out via the broker's offset retention — the common
 * rollout path is graceful, which is what generates the churn we are fixing.
 */
@Slf4j
@Component
public class SseConsumerGroupCleaner {

    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final KafkaGroupIds groupIds;
    private final String bootstrapServers;
    private final String securityProtocol;
    private final String saslMechanism;
    private final String saslJaasConfig;

    public SseConsumerGroupCleaner(KafkaListenerEndpointRegistry listenerRegistry, KafkaGroupIds groupIds,
            @Value("${spring.kafka.bootstrap-servers:localhost:9094}") String bootstrapServers,
            @Value("${spring.kafka.properties.security.protocol:}") String securityProtocol,
            @Value("${spring.kafka.properties.sasl.mechanism:}") String saslMechanism,
            @Value("${spring.kafka.properties.sasl.jaas.config:}") String saslJaasConfig) {
        this.listenerRegistry = listenerRegistry;
        this.groupIds = groupIds;
        this.bootstrapServers = bootstrapServers;
        this.securityProtocol = securityProtocol;
        this.saslMechanism = saslMechanism;
        this.saslJaasConfig = saslJaasConfig;
    }

    @EventListener
    public void onShutdown(ContextClosedEvent event) {
        List<String> groups = List.of(groupIds.getTransactionSseGroup(), groupIds.getDisputeSseGroup());
        try {
            // Stop listeners first so the consumers leave their groups — a group with
            // live members cannot be deleted (GroupNotEmptyException).
            listenerRegistry.stop();
        } catch (Exception e) {
            log.warn("Could not stop Kafka listener containers cleanly before group cleanup: {}", e.getMessage());
        }

        try (Admin admin = Admin.create(adminProps())) {
            admin.deleteConsumerGroups(groups).all().get(10, TimeUnit.SECONDS);
            log.info("Deleted this pod's SSE consumer groups on shutdown: {}", groups);
        } catch (Exception e) {
            // Best-effort: a leftover group will age out via offsets.retention or the
            // next pod's cleanup. Never let this block shutdown.
            log.warn("Best-effort SSE consumer-group cleanup failed for {} (will age out): {}", groups, e.getMessage());
        }
    }

    private Map<String, Object> adminProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 8000);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        if (securityProtocol != null && !securityProtocol.isBlank()) {
            props.put("security.protocol", securityProtocol);
            if (saslMechanism != null && !saslMechanism.isBlank()) {
                props.put("sasl.mechanism", saslMechanism);
            }
            if (saslJaasConfig != null && !saslJaasConfig.isBlank()) {
                props.put("sasl.jaas.config", saslJaasConfig);
            }
        }
        return props;
    }
}

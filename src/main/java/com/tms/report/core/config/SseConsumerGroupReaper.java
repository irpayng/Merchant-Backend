package com.tms.report.core.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.common.ConsumerGroupState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net for the per-pod SSE consumer groups.
 * {@link SseConsumerGroupCleaner} deletes a pod's groups on graceful shutdown,
 * which covers the common rollout path; but an ungraceful death (SIGKILL / OOM
 * / node loss) cannot run that hook and leaves the group behind to accumulate
 * phantom lag until broker offset retention ages it out (~7 days).
 *
 * <p>
 * This reaper periodically deletes SSE consumer groups that are <b>empty</b>
 * (no live members) — a live pod's group always has a member, so an empty SSE
 * group is an orphan. To eliminate the only race (a pod that has created its
 * group but not yet joined it appears briefly empty), a group is deleted only
 * when it has been observed empty on <b>two consecutive</b> scans, so a
 * starting pod is never reaped. Deletion is idempotent across replicas (all
 * pods may run this; a double-delete is harmless), best-effort, and never
 * throws.
 */
@Slf4j
@Component
public class SseConsumerGroupReaper {

    private static final List<String> SSE_PREFIXES = List.of(KafkaGroupIds.TRANSACTION_SSE_PREFIX,
            KafkaGroupIds.DISPUTE_SSE_PREFIX);

    private final String bootstrapServers;
    private final String securityProtocol;
    private final String saslMechanism;
    private final String saslJaasConfig;

    /**
     * Groups seen empty on the previous scan — only these become deletable this
     * scan.
     */
    private volatile Set<String> previouslyEmpty = Set.of();

    public SseConsumerGroupReaper(@Value("${spring.kafka.bootstrap-servers:localhost:9094}") String bootstrapServers,
            @Value("${spring.kafka.properties.security.protocol:}") String securityProtocol,
            @Value("${spring.kafka.properties.sasl.mechanism:}") String saslMechanism,
            @Value("${spring.kafka.properties.sasl.jaas.config:}") String saslJaasConfig) {
        this.bootstrapServers = bootstrapServers;
        this.securityProtocol = securityProtocol;
        this.saslMechanism = saslMechanism;
        this.saslJaasConfig = saslJaasConfig;
    }

    @Scheduled(initialDelayString = "${sse.group.reaper.initial-delay-ms:600000}", fixedDelayString = "${sse.group.reaper.interval-ms:1800000}")
    public void reap() {
        try (Admin admin = Admin.create(adminProps())) {
            Set<String> sseGroups = admin.listConsumerGroups().all().get(10, TimeUnit.SECONDS).stream()
                    .map(ConsumerGroupListing::groupId).filter(this::isSseGroup).collect(Collectors.toSet());
            if (sseGroups.isEmpty()) {
                previouslyEmpty = Set.of();
                return;
            }

            Map<String, ConsumerGroupDescription> described = admin.describeConsumerGroups(sseGroups).all().get(10,
                    TimeUnit.SECONDS);

            Set<String> emptyNow = described.values().stream()
                    .filter(d -> d.state() == ConsumerGroupState.EMPTY && d.members().isEmpty())
                    .map(ConsumerGroupDescription::groupId).collect(Collectors.toSet());

            // Two-strike rule: delete only groups empty on this scan AND the previous
            // one, so a pod mid-startup (briefly empty) is never reaped.
            Set<String> deletable = new HashSet<>(emptyNow);
            deletable.retainAll(previouslyEmpty);

            if (!deletable.isEmpty()) {
                admin.deleteConsumerGroups(deletable).all().get(10, TimeUnit.SECONDS);
                log.info("Reaped {} orphaned SSE consumer group(s): {}", deletable.size(), deletable);
            }
            previouslyEmpty = emptyNow;
        } catch (Exception e) {
            log.warn("SSE consumer-group reaper run failed (will retry next interval): {}", e.getMessage());
        }
    }

    private boolean isSseGroup(String groupId) {
        return SSE_PREFIXES.stream().anyMatch(groupId::startsWith);
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

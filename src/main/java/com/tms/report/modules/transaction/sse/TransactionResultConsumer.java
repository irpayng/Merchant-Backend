package com.tms.report.modules.transaction.sse;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumer for transaction-result events. Receives events from the
 * processor after a transaction completes/fails and pushes them to admin SSE
 * subscribers via {@link TransactionSseRegistry}.
 *
 * The event contains minimal data (reference, status, amount). We query the
 * replicated transactions table to build the full DTO for the frontend.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionResultConsumer {

    private final TransactionSseRegistry sseRegistry;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    /**
     * Per-pod consumer group: the group id is suffixed with the pod hostname (or a
     * random UUID locally) so every replica is its own consumer group and therefore
     * receives EVERY transaction-result message. This is required for SSE fan-out —
     * the {@link TransactionSseRegistry} holds emitters in memory per pod, so a
     * message consumed by only one pod (the case with a shared group id, since
     * transaction-result has a single partition) would never reach clients
     * connected to the other pods. With {@code auto.offset.reset=latest} a fresh
     * group only sees new events, so there is no history replay on restart.
     */
    @KafkaListener(topics = "transaction-result", groupId = "#{@kafkaGroupIds.transactionSseGroup}")
    public void consume(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String reference = node.has("reference") ? node.get("reference").textValue() : null;
            String status = node.has("status") ? node.get("status").textValue() : null;

            if (reference == null || status == null) {
                log.debug("Ignoring transaction-result event with missing reference or status");
                return;
            }

            // Build a lightweight event for the frontend with data from the Kafka event
            // The replicated DB may have a slight delay, so we use the Kafka event data
            // directly for the SSE push and let the frontend merge it.
            String amount = null;
            if (node.has("amount") && !node.get("amount").isNull()) {
                amount = node.get("amount").asText();
            }

            String productCode = node.has("product_code") ? node.get("product_code").textValue() : null;
            String providerCode = node.has("provider_code") ? node.get("provider_code").textValue() : null;
            String channel = node.has("channel") ? node.get("channel").textValue() : null;
            Long userId = node.has("user_id") && !node.get("user_id").isNull() ? node.get("user_id").asLong() : null;

            // Build the SSE payload — a lightweight transaction summary
            String productName = productCode != null
                    ? capitalize(productCode.replace("_", " ").replace("-", " "))
                    : null;
            String providerName = providerCode != null
                    ? capitalize(providerCode.replace("provider-", "").replace("-", " "))
                    : null;

            // Look up user name from replicated profiles table
            String userName = userId != null ? lookupUserName(userId) : null;

            String json = buildJson(reference, status, amount, productCode, productName, providerCode, providerName,
                    channel, userId, userName, node);

            sseRegistry.broadcast("new_transaction", json);

            log.debug("Pushed transaction-result to SSE: reference={}, status={}", reference, status);
        } catch (Exception e) {
            log.error("Failed to process transaction-result event for SSE: {}", e.getMessage());
        }
    }

    private String buildJson(String reference, String status, String amount, String productCode, String productName,
            String providerCode, String providerName, String channel, Long userId, String userName, JsonNode node) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"reference\":\"").append(escapeJson(reference)).append("\"");
        sb.append(",\"status\":{\"code\":\"").append(escapeJson(status)).append("\",\"name\":\"")
                .append(escapeJson(getStatusName(status))).append("\"}");

        if (amount != null) {
            sb.append(",\"amount\":\"").append(escapeJson(amount)).append("\"");
        }
        if (productCode != null) {
            sb.append(",\"product\":{\"code\":\"").append(escapeJson(productCode)).append("\",\"name\":\"")
                    .append(escapeJson(productName != null ? productName : productCode)).append("\"}");
        }
        if (providerCode != null) {
            sb.append(",\"provider\":{\"code\":\"").append(escapeJson(providerCode)).append("\",\"name\":\"")
                    .append(escapeJson(providerName != null ? providerName : providerCode)).append("\"}");
        }
        if (channel != null) {
            sb.append(",\"channel\":{\"code\":\"").append(escapeJson(channel)).append("\",\"name\":\"")
                    .append(escapeJson(capitalize(channel))).append("\"}");
        }
        if (userId != null) {
            sb.append(",\"user\":{\"id\":").append(userId);
            if (userName != null) {
                sb.append(",\"name\":\"").append(escapeJson(userName)).append("\"");
            } else {
                sb.append(",\"name\":\"\"");
            }
            sb.append("}");
        }

        // Fee / commission breakdown — carried on the transaction-result event the
        // processor publishes. Emitting them here lets the dashboard render the
        // fee columns on the realtime row immediately instead of showing ₦0.00
        // until the table is refreshed against the list API. provider_cost is the
        // one exception (it is derived from ledger_entries written asynchronously
        // by the ledger service), so it stays 0 on the realtime row and fills in
        // on the next refresh.
        appendAmountField(sb, node, "service_fee");
        appendAmountField(sb, node, "agent_commission");
        appendAmountField(sb, node, "aggregator_commission");
        appendAmountField(sb, node, "super_aggregator_commission");
        appendAmountField(sb, node, "company_commission");
        appendAmountField(sb, node, "amount_to_pay");

        sb.append("}");
        return sb.toString();
    }

    /**
     * Appends a numeric field from the Kafka event as a JSON string (matching the
     * shape the dashboard's currency formatter expects). Always emits the key —
     * defaulting to "0" when absent or null — so the realtime row has a concrete
     * value for every fee column rather than {@code undefined}.
     */
    private void appendAmountField(StringBuilder sb, JsonNode node, String key) {
        String value = "0";
        if (node != null && node.has(key) && !node.get(key).isNull()) {
            value = node.get(key).asText();
        }
        sb.append(",\"").append(key).append("\":\"").append(escapeJson(value)).append("\"");
    }

    /**
     * Look up user display name from the replicated profiles table. Returns
     * "first_name last_name" or the user's email as fallback. Returns null if user
     * not found.
     */
    private String lookupUserName(Long userId) {
        try {
            var query = entityManager.createNativeQuery(
                    "SELECT COALESCE(TRIM(COALESCE(p.first_name, '') || ' ' || COALESCE(p.last_name, '')), u.email) "
                            + "FROM users u LEFT JOIN profiles p ON p.user_id = u.id WHERE u.id = :uid");
            query.setParameter("uid", userId);
            Object result = query.getSingleResult();
            if (result != null) {
                String name = result.toString().trim();
                return name.isEmpty() ? null : name;
            }
        } catch (Exception e) {
            log.debug("Failed to lookup user name for userId={}: {}", userId, e.getMessage());
        }
        return null;
    }

    private String getStatusName(String code) {
        if (code == null)
            return "Unknown";
        return switch (code) {
            case "completed" -> "Completed";
            case "failed" -> "Failed";
            case "processing" -> "Processing";
            case "reversed" -> "Reversed";
            case "claimed" -> "Claimed";
            default -> capitalize(code);
        };
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private String escapeJson(String value) {
        if (value == null)
            return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t",
                "\\t");
    }
}

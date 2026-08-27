package com.tms.report.modules.dispute.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/**
 * The wire shape of a dispute chat message for the merchant UI.
 *
 * <p>
 * The same record is produced from two sources — the replicated table (REST
 * fetch) and the Kafka event (SSE push) — which guarantees the UI sees an
 * identical object either way.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConversationDto(@JsonProperty("id") Long id, @JsonProperty("dispute_id") Long disputeId,
        @JsonProperty("user_id") Long userId, @JsonProperty("sender_type") String senderType,
        @JsonProperty("sender_name") String senderName, @JsonProperty("message") String message,
        @JsonProperty("attachment") Attachment attachment, @JsonProperty("created_at") String createdAt) {

    /**
     * Attachment metadata plus a URL. {@code isImage} drives inline rendering vs a
     * download chip in the UI.
     */
    public record Attachment(@JsonProperty("url") String url, @JsonProperty("name") String name,
            @JsonProperty("type") String type, @JsonProperty("size") Long size,
            @JsonProperty("is_image") boolean isImage) {
    }

    /**
     * Build from the {@code dispute-conversation} Kafka event (SSE push).
     */
    public static ConversationDto from(JsonNode node) {
        return new ConversationDto(node.path("id").asLong(), node.path("disputeId").asLong(),
                node.hasNonNull("userId") ? node.get("userId").asLong() : null,
                normalizeSender(text(node, "senderType")), text(node, "senderName"), text(node, "message"),
                attachment(text(node, "attachmentKey"), text(node, "attachmentName"), text(node, "attachmentType"),
                        node.hasNonNull("attachmentSize") ? node.get("attachmentSize").asLong() : null),
                text(node, "createdAt"));
    }

    private static Attachment attachment(String key, String name, String type, Long size) {
        if (key == null || key.isBlank()) {
            return null;
        }
        // For merchant, the attachment URL comes pre-signed from the message
        String url = key;
        boolean isImage = type != null && type.toLowerCase().startsWith("image/");
        return new Attachment(url, name, type, size, isImage);
    }

    /**
     * The dispute service says {@code agent} for a support reply; the UI has always
     * spoken {@code admin}. Normalising server-side means the UI no longer has to
     * map it in three places.
     */
    private static String normalizeSender(String senderType) {
        if (senderType == null || senderType.isBlank()) {
            return "user";
        }
        return "agent".equals(senderType) ? "admin" : senderType;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }
}

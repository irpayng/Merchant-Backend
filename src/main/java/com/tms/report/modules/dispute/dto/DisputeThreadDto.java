package com.tms.report.modules.dispute.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One row in the merchant dispute inbox list. Contains all data needed to
 * render the thread list without additional queries.
 */
public record DisputeThreadDto(@JsonProperty("id") Long id, @JsonProperty("reason") String reason,
        @JsonProperty("status_code") String statusCode, @JsonProperty("last_message") String lastMessage,
        @JsonProperty("last_message_sender") String lastMessageSender,
        @JsonProperty("last_message_at") String lastMessageAt,
        @JsonProperty("last_message_has_attachment") boolean lastMessageHasAttachment,
        @JsonProperty("unread_count") long unreadCount, @JsonProperty("created_at") String createdAt) {

    @JsonProperty("is_unread")
    public boolean isUnread() {
        return unreadCount > 0;
    }

    @JsonProperty("is_closed")
    public boolean isClosed() {
        return "closed".equals(statusCode) || "resolved".equals(statusCode);
    }
}

package com.tms.report.modules.grpc.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client for calling tms-notification internal REST endpoints. Uses the
 * /internal/notifications path which bypasses JWT authentication and instead
 * uses X-Internal-Key header for service-to-service authorization.
 *
 * <p>
 * Fails gracefully: if the notification service is unavailable, returns empty
 * results rather than throwing exceptions — notifications are non-critical.
 */
@Slf4j
@Service
public class NotificationHttpClient {

    private final String baseUrl;
    private final String internalApiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public NotificationHttpClient(@Value("${notification.service.url:http://notification-service}") String baseUrl,
            @Value("${grpc.api.key:secret}") String internalApiKey, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.internalApiKey = internalApiKey;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
        log.info("NotificationHttpClient initialized: {}", baseUrl);
    }

    /**
     * Fetch paginated notifications for a user.
     *
     * @param userId
     *            the user ID to fetch notifications for
     * @param page
     *            page number (1-indexed)
     * @param perPage
     *            items per page
     * @return response map containing data array, pagination info, and unread_count
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listNotifications(Long userId, int page, int perPage) {
        String url = baseUrl + "/internal/notifications/user/" + userId + "?page=" + page + "&per_page=" + perPage;
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("X-Internal-Key", internalApiKey).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            if (response.statusCode() >= 400) {
                String message = result.getOrDefault("message", "Failed to fetch notifications").toString();
                log.error("Notification list failed for user {}: {} - {}", userId, response.statusCode(), message);
                return emptyListResponse(page, perPage);
            }
            return result;
        } catch (Exception e) {
            log.warn("Notification service unavailable for user {}: {}", userId, e.getMessage());
            return emptyListResponse(page, perPage);
        }
    }

    /**
     * Get a single notification by ID.
     *
     * @param userId
     *            the user ID
     * @param notificationId
     *            the notification ID
     * @return response map containing the notification data
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getNotification(Long userId, Long notificationId) {
        // Use the list endpoint with filters — internal API doesn't have single-get
        // For now, fetch from list and filter client-side
        Map<String, Object> result = listNotifications(userId, 1, 100);
        @SuppressWarnings("unchecked")
        var data = (java.util.List<Map<String, Object>>) result.get("data");
        if (data != null) {
            for (Map<String, Object> notification : data) {
                Object id = notification.get("id");
                if (id != null && id.toString().equals(notificationId.toString())) {
                    return Map.of("data", notification, "code", 200, "message", "success");
                }
            }
        }
        return Map.of("code", 404, "message", "Notification not found");
    }

    /**
     * Mark a single notification as read.
     *
     * @param userId
     *            the user ID
     * @param notificationId
     *            the notification ID to mark as read
     * @return response map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> markAsRead(Long userId, Long notificationId) {
        String url = baseUrl + "/internal/notifications/user/" + userId + "/" + notificationId + "/mark-as-read";
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("X-Internal-Key", internalApiKey)
                    .method("PATCH", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            if (response.statusCode() >= 400) {
                String message = result.getOrDefault("message", "Failed to mark as read").toString();
                log.warn("Failed to mark notification {} as read: {}", notificationId, message);
            }
            return result;
        } catch (Exception e) {
            log.warn("Notification service unavailable: {}", e.getMessage());
            return Map.of("code", 503, "message", "Notification service temporarily unavailable");
        }
    }

    /**
     * Mark all notifications as read for a user.
     *
     * @param userId
     *            the user ID
     * @return response map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> markAllAsRead(Long userId) {
        String url = baseUrl + "/internal/notifications/user/" + userId + "/mark-all-as-read";
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("X-Internal-Key", internalApiKey)
                    .method("PATCH", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            if (response.statusCode() >= 400) {
                String message = result.getOrDefault("message", "Failed to mark all as read").toString();
                log.warn("Failed to mark all notifications as read for user {}: {}", userId, message);
            }
            return result;
        } catch (Exception e) {
            log.warn("Notification service unavailable: {}", e.getMessage());
            return Map.of("code", 503, "message", "Notification service temporarily unavailable");
        }
    }

    /**
     * Get count of unread notifications for a user.
     *
     * @param userId
     *            the user ID
     * @return unread count
     */
    @SuppressWarnings("unchecked")
    public int getUnreadCount(Long userId) {
        String url = baseUrl + "/internal/notifications/user/" + userId + "/unread-count";
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("X-Internal-Key", internalApiKey).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 400) {
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                Object data = result.get("data");
                if (data instanceof Map) {
                    Object unreadCount = ((Map<?, ?>) data).get("unread_count");
                    if (unreadCount instanceof Number) {
                        return ((Number) unreadCount).intValue();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Notification service unavailable for unread count: {}", e.getMessage());
        }
        return 0;
    }

    private Map<String, Object> emptyListResponse(int page, int perPage) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", new ArrayList<>());
        response.put("current_page", page);
        response.put("per_page", perPage);
        response.put("total", 0);
        response.put("last_page", 1);
        response.put("unread_count", 0);
        response.put("code", 200);
        response.put("message", "success");
        return response;
    }
}

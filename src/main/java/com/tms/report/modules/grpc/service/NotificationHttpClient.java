package com.tms.report.modules.grpc.service;

import com.tms.report.modules.grpc.exception.GrpcException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client for calling tms-notification REST endpoints. Used to fetch and
 * manage notifications for the logged-in merchant user.
 */
@Slf4j
@Service
public class NotificationHttpClient {

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public NotificationHttpClient(@Value("${notification.service.url:http://notification-service}") String baseUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
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
        String url = baseUrl + "/notifications?page=" + page + "&per_page=" + perPage;
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("X-User-Id", String.valueOf(userId)).GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            if (response.statusCode() >= 400) {
                String message = result.getOrDefault("message", "Failed to fetch notifications").toString();
                log.error("Notification list failed for user {}: {} - {}", userId, response.statusCode(), message);
                throw new GrpcException("Failed to fetch notifications: " + message, "FETCH_FAILED",
                        Map.of("userId", userId));
            }
            return result;
        } catch (GrpcException e) {
            throw e;
        } catch (Exception e) {
            log.error("Notification service unavailable: {}", e.getMessage());
            throw new GrpcException("Notification service unavailable: " + e.getMessage(), "UNAVAILABLE",
                    Map.of("userId", userId));
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
        String url = baseUrl + "/notifications/" + notificationId;
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("X-User-Id", String.valueOf(userId)).GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            if (response.statusCode() >= 400) {
                String message = result.getOrDefault("message", "Notification not found").toString();
                throw new GrpcException("Failed to fetch notification: " + message, "NOT_FOUND",
                        Map.of("notificationId", notificationId));
            }
            return result;
        } catch (GrpcException e) {
            throw e;
        } catch (Exception e) {
            log.error("Notification service unavailable: {}", e.getMessage());
            throw new GrpcException("Notification service unavailable: " + e.getMessage(), "UNAVAILABLE",
                    Map.of("notificationId", notificationId));
        }
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
        String url = baseUrl + "/notifications/" + notificationId + "/mark-as-read";
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("X-User-Id", String.valueOf(userId))
                    .method("PATCH", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            if (response.statusCode() >= 400) {
                String message = result.getOrDefault("message", "Failed to mark as read").toString();
                throw new GrpcException("Failed to mark notification as read: " + message, "UPDATE_FAILED",
                        Map.of("notificationId", notificationId));
            }
            return result;
        } catch (GrpcException e) {
            throw e;
        } catch (Exception e) {
            log.error("Notification service unavailable: {}", e.getMessage());
            throw new GrpcException("Notification service unavailable: " + e.getMessage(), "UNAVAILABLE",
                    Map.of("notificationId", notificationId));
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
        String url = baseUrl + "/notifications/mark-all-as-read";
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("X-User-Id", String.valueOf(userId))
                    .method("PATCH", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            if (response.statusCode() >= 400) {
                String message = result.getOrDefault("message", "Failed to mark all as read").toString();
                throw new GrpcException("Failed to mark all notifications as read: " + message, "UPDATE_FAILED",
                        Map.of("userId", userId));
            }
            return result;
        } catch (GrpcException e) {
            throw e;
        } catch (Exception e) {
            log.error("Notification service unavailable: {}", e.getMessage());
            throw new GrpcException("Notification service unavailable: " + e.getMessage(), "UNAVAILABLE",
                    Map.of("userId", userId));
        }
    }

    /**
     * Get count of unread notifications for a user.
     *
     * @param userId
     *            the user ID
     * @return unread count
     */
    public int getUnreadCount(Long userId) {
        Map<String, Object> result = listNotifications(userId, 1, 1);
        Object unreadCount = result.get("unread_count");
        if (unreadCount instanceof Number) {
            return ((Number) unreadCount).intValue();
        }
        return 0;
    }
}

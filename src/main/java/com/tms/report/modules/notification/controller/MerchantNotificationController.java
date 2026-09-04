package com.tms.report.modules.notification.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.grpc.service.NotificationHttpClient;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for merchant in-app notifications. Proxies requests to the
 * tms-notification service using the logged-in merchant's user ID.
 *
 * <p>
 * This mirrors the mobile app's notification functionality, allowing merchants
 * to view and manage their notifications on the dashboard.
 */
@RestController
@RequestMapping("/my-notifications")
@RequiredArgsConstructor
public class MerchantNotificationController {

    private final NotificationHttpClient notificationHttpClient;
    private final MerchantScope merchantScope;

    /**
     * List notifications for the logged-in merchant.
     *
     * @param params
     *            query params including page (default 1) and limit/per_page
     *            (default 15)
     * @return paginated list of notifications with unread_count
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam Map<String, String> params) {
        Long userId = getMerchantUserId();
        int page = Integer.parseInt(params.getOrDefault("page", "1"));
        int perPage = Integer.parseInt(params.getOrDefault("limit", params.getOrDefault("per_page", "15")));
        return notificationHttpClient.listNotifications(userId, page, perPage);
    }

    /**
     * Get a single notification.
     *
     * @param id
     *            notification ID
     * @return the notification
     */
    @GetMapping("/{id}")
    public Map<String, Object> show(@PathVariable Long id) {
        Long userId = getMerchantUserId();
        return notificationHttpClient.getNotification(userId, id);
    }

    /**
     * Mark a single notification as read.
     *
     * @param id
     *            notification ID
     * @return success response
     */
    @PatchMapping("/{id}/mark-as-read")
    public Map<String, Object> markAsRead(@PathVariable Long id) {
        Long userId = getMerchantUserId();
        return notificationHttpClient.markAsRead(userId, id);
    }

    /**
     * Mark all notifications as read.
     *
     * @return success response
     */
    @PatchMapping("/mark-all-as-read")
    public Map<String, Object> markAllAsRead() {
        Long userId = getMerchantUserId();
        return notificationHttpClient.markAllAsRead(userId);
    }

    /**
     * Get count of unread notifications.
     *
     * @return unread count
     */
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        Long userId = getMerchantUserId();
        int count = notificationHttpClient.getUnreadCount(userId);
        return ApiResponse.success(Map.of("unread_count", count));
    }

    private Long getMerchantUserId() {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return merchantId;
    }
}

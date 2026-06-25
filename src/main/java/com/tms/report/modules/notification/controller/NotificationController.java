package com.tms.report.modules.notification.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.export.XlsxExporter;
import com.tms.report.core.filter.SpecBuilder;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.grpc.service.GrpcClient;
import com.tms.report.modules.notification.model.Notification;
import com.tms.report.modules.notification.repository.NotificationRepository;
import com.tms.report.modules.user.model.User;
import com.tms.report.modules.user.repository.ProfileRepository;
import com.tms.report.modules.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final GrpcClient grpcClient;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));
        var spec = SpecBuilder.<Notification>fromParams(params, Map.of());
        return PagedResponse.from(notificationRepository.findAll(spec,
                PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @GetMapping("/download")
    public void download(@RequestParam Map<String, String> params, HttpServletResponse response) throws Exception {
        var spec = SpecBuilder.<Notification>fromParams(params, Map.of());
        var sort = Sort.by(Sort.Direction.DESC, "createdAt");
        XlsxExporter.streamPaged(response, "notifications",
                new String[]{"ID", "Type", "Notifiable Type", "Notifiable ID", "Read At", "Created At"}, 1000,
                (page, size) -> notificationRepository.findAll(spec, PageRequest.of(page, size, sort)).getContent(),
                row -> new String[]{String.valueOf(row.getId()), row.getType(), row.getNotifiableType(),
                        row.getNotifiableId() != null ? row.getNotifiableId().toString() : "",
                        row.getReadAt() != null ? row.getReadAt().toString() : "",
                        row.getCreatedAt() != null ? row.getCreatedAt().toString() : ""});
    }

    @GetMapping("/{id}")
    public ApiResponse<Notification> show(@PathVariable Long id) {
        return ApiResponse.success(notificationRepository.findById(id).orElseThrow());
    }

    @LogActivity(action = "send", description = "{admin} sent a notification")
    @PostMapping
    public ApiResponse<Map<String, Object>> store(@RequestBody Map<String, Object> body) {
        String notifiableId = body.get("notifiable_id") != null ? body.get("notifiable_id").toString() : "";

        // Resolve user contact details for SMS/email channels
        User user = null;
        try {
            long userId = Long.parseLong(notifiableId);
            user = userRepository.findById(userId).orElse(null);
        } catch (NumberFormatException ignored) {
        }

        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("notifiable_id", notifiableId);
        data.put("subject", body.getOrDefault("subject", "Notification"));
        data.put("message", body.getOrDefault("message", ""));
        data.put("channels", body.getOrDefault("channels", "database"));
        // Pass resolved contact details so notification service can use them
        Map<String, String> contactData = new HashMap<>();
        if (user.getPhoneNumber() != null) {
            contactData.put("phone", user.getPhoneNumber());
        }
        if (user.getEmail() != null) {
            contactData.put("email", user.getEmail());
        }
        // Resolve profile name for email personalization
        profileRepository.findByUserId(user.getId()).ifPresent(profile -> {
            String fullName = profile.getFullName();
            if (fullName != null && !fullName.isBlank()) {
                contactData.put("name", fullName);
            }
        });
        data.put("data", contactData);
        return ApiResponse.success(grpcClient.sendNotification(data));
    }

    @LogActivity(action = "broadcast", description = "{admin} broadcasted a notification")
    @PostMapping("/broadcast")
    public ApiResponse<Map<String, Object>> broadcast(@RequestBody Map<String, Object> body) {
        Map<String, Object> data = new HashMap<>();
        data.put("notifiable_id", body.get("notifiable_id"));
        data.put("subject", body.getOrDefault("subject", "Broadcast"));
        data.put("message", body.getOrDefault("message", ""));
        data.put("channels", body.getOrDefault("channels", "database"));
        data.put("data", body.getOrDefault("data", Map.of()));
        return ApiResponse.success(grpcClient.sendNotification(data));
    }

    @PatchMapping("/{id}/mark-as-read")
    public ApiResponse<Notification> markAsRead(@PathVariable Long id) {
        Notification notification = notificationRepository.findById(id).orElseThrow();
        notification.setReadAt(LocalDateTime.now());
        return ApiResponse.success(notificationRepository.save(notification));
    }

    @PatchMapping("/mark-all-as-read")
    public ApiResponse<Void> markAllAsRead() {
        notificationRepository.markAllAsRead();
        return ApiResponse.success(null);
    }
}

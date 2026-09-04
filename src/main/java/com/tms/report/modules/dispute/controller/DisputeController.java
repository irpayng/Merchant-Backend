package com.tms.report.modules.dispute.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.dispute.dto.AddConversationDto;
import com.tms.report.modules.dispute.dto.CreateDisputeDto;
import com.tms.report.modules.dispute.dto.DisputeThreadDto;
import com.tms.report.modules.dispute.service.DisputeService;
import com.tms.report.modules.merchantuser.model.MerchantUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/disputes")
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeService disputeService;
    private final MerchantScope merchantScope;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params, HttpServletRequest request) {
        extractDates(request, params);
        Page<Map<String, Object>> page = disputeService.index(params);
        Map<String, Object> extra = new java.util.LinkedHashMap<>();
        extra.put("filters", disputeService.filters());
        return PagedResponse.from(page, "/disputes", extra);
    }

    /**
     * GET /disputes/threads — the inbox list for the Messenger-style chat UI.
     * Mapped above /{id} so Spring resolves this literal path before the path
     * variable.
     */
    @GetMapping("/threads")
    public ApiResponse<List<DisputeThreadDto>> threads(@RequestParam(required = false) String filter,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "60") int limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {
        return ApiResponse.success(disputeService.threads(currentMerchantUserId(), filter, search, limit, offset));
    }

    /**
     * GET /disputes/unread-count — total unread messages for this merchant user.
     */
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        return ApiResponse.success(Map.of("unread_count", disputeService.totalUnread(currentMerchantUserId())));
    }

    /** GET /disputes/download — export disputes to xlsx. */
    @GetMapping("/download")
    public void download(@RequestParam Map<String, String> params, HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) throws Exception {
        extractDates(request, params);
        disputeService.export(params, response);
    }

    private void extractDates(HttpServletRequest request, Map<String, String> params) {
        String[] dates = request.getParameterValues("dates[]");
        if (dates != null && dates.length >= 2) {
            params.put("dates[0]", dates[0]);
            params.put("dates[1]", dates[1]);
        }
    }

    /**
     * PATCH /disputes/{id}/mark-read — mark a dispute as read by this merchant
     * user.
     */
    @PatchMapping("/{id}/mark-read")
    public ApiResponse<Map<String, Object>> markRead(@PathVariable Long id) {
        disputeService.markRead(currentMerchantUserId(), id);
        return ApiResponse
                .success(Map.of("dispute_id", id, "unread_count", disputeService.totalUnread(currentMerchantUserId())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> show(@PathVariable Long id) {
        Map<String, Object> dispute = disputeService.show(id);
        if (dispute == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, "Dispute not found."));
        }
        // Mark as read when opening the dispute
        disputeService.markRead(currentMerchantUserId(), id);
        return ResponseEntity.ok(ApiResponse.success(dispute));
    }

    /** The signed-in merchant user's id, used for per-user unread state. */
    private Long currentMerchantUserId() {
        MerchantUser mu = merchantScope.current();
        return mu != null ? mu.getId() : null;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@Valid @RequestBody CreateDisputeDto dto) {
        Map<String, Object> result = disputeService.create(dto);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (!success) {
            String message = result.getOrDefault("message", "Failed to create dispute").toString();
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.error(422, message));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<Map<String, Object>>builder().code(201)
                        .message(result.getOrDefault("message", "Dispute created successfully").toString()).data(result)
                        .build());
    }

    @PostMapping("/{id}/add-conversation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addConversation(@PathVariable Long id,
            @Valid @RequestBody AddConversationDto dto) {
        Map<String, Object> result = disputeService.addConversation(id, dto.getMessage());
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (!success) {
            String message = result.getOrDefault("message", "Failed to add conversation").toString();
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.error(422, message));
        }
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder().code(200).message("Conversation added")
                .data(result).build());
    }

    @PatchMapping("/{id}/close")
    public ResponseEntity<ApiResponse<Map<String, Object>>> close(@PathVariable Long id) {
        Map<String, Object> result = disputeService.close(id);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (!success) {
            String message = result.getOrDefault("message", "Failed to close dispute").toString();
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.error(422, message));
        }
        return ResponseEntity.ok(
                ApiResponse.<Map<String, Object>>builder().code(200).message("Dispute closed").data(result).build());
    }
}

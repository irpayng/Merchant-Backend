package com.tms.report.modules.dispute.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.modules.dispute.dto.AddConversationDto;
import com.tms.report.modules.dispute.dto.CreateDisputeDto;
import com.tms.report.modules.dispute.service.DisputeService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/disputes")
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeService disputeService;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        Page<Map<String, Object>> page = disputeService.index(params);
        return PagedResponse.from(page, "/disputes");
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> show(@PathVariable Long id) {
        Map<String, Object> dispute = disputeService.show(id);
        if (dispute == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, "Dispute not found."));
        }
        return ResponseEntity.ok(ApiResponse.success(dispute));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('create_dispute')")
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

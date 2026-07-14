package com.tms.report.modules.dispute.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.modules.dispute.dto.CreateDisputeDto;
import com.tms.report.modules.dispute.service.DisputeService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/disputes")
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeService disputeService;

    @PostMapping
    @PreAuthorize("hasAuthority('create_dispute')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@Valid @RequestBody CreateDisputeDto dto) {
        Map<String, Object> result = disputeService.create(dto);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (!success) {
            String message = result.getOrDefault("message", "Failed to create dispute").toString();
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(ApiResponse.error(422, message));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<Map<String, Object>>builder()
                        .code(201)
                        .message(result.getOrDefault("message", "Dispute created successfully").toString())
                        .data(result)
                        .build());
    }
}

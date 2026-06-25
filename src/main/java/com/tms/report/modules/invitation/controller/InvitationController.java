package com.tms.report.modules.invitation.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.grpc.service.GrpcClient;
import com.tms.report.modules.invitation.repository.InvitationRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationRepository invitationRepository;
    private final GrpcClient grpcClient;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));
        return PagedResponse.from(
                invitationRepository.findAll(PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @LogActivity(action = "invite", description = "{admin} sent an invitation to {body.email}")
    @PostMapping
    public ApiResponse<Map<String, Object>> store(@RequestBody Map<String, Object> body) {
        List<String> emails = body.containsKey("emails") ? (List<String>) body.get("emails") : List.of();
        List<String> phoneNumbers = body.containsKey("phone_numbers")
                ? (List<String>) body.get("phone_numbers")
                : List.of();
        return ApiResponse.success(grpcClient.inviteUser(emails, phoneNumbers));
    }
}

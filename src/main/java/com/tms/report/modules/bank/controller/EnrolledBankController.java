package com.tms.report.modules.bank.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.bank.model.Bank;
import com.tms.report.modules.bank.service.EnrolledBankService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Enrolled tenant-bank registry. Enrolling/disabling a bank is an IRPay-global
 * action ({@code manage_admin}); the list is available to any authenticated
 * admin so the Portal Users form can populate its bank dropdown.
 */
@RestController
@RequestMapping("/enrolled-banks")
@RequiredArgsConstructor
public class EnrolledBankController {

    private final EnrolledBankService enrolledBankService;

    @GetMapping
    public ApiResponse<List<Bank>> index() {
        return ApiResponse.success(enrolledBankService.list());
    }

    @LogActivity(action = "enroll", description = "{admin} enrolled bank {body.name}")
    @PostMapping
    @PreAuthorize("hasAuthority('manage_admin')")
    public ApiResponse<Bank> enroll(@RequestBody Map<String, String> body) {
        Bank bank = enrolledBankService.enroll(body.get("code"), body.get("name"), body.get("contact_email"));
        return ApiResponse.success(bank);
    }

    @LogActivity(action = "updateStatus", description = "{admin} updated bank status")
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('manage_admin')")
    public ApiResponse<Bank> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.success(enrolledBankService.updateStatus(id, body.get("status")));
    }
}

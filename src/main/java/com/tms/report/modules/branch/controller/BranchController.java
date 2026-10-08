package com.tms.report.modules.branch.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.branch.dto.BranchCreateRequest;
import com.tms.report.modules.branch.dto.BranchResponse;
import com.tms.report.modules.branch.dto.BranchSettlementRequest;
import com.tms.report.modules.branch.dto.BranchUpdateRequest;
import com.tms.report.modules.branch.service.BranchService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Branch management endpoints for merchant dashboard.
 *
 * <p>
 * Merchants can manage their branches (physical locations). Each branch can
 * have its own terminals, TIDs, operators, and settlement account assigned.
 * </p>
 */
@RestController
@RequestMapping("/branches")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('manage_branch')")
public class BranchController {

    private final BranchService branchService;
    private final MerchantScope merchantScope;

    /**
     * List all branches for the current merchant.
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam Map<String, String> params) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return PagedResponse.empty("/branches");
        }
        return PagedResponse.from(branchService.listBranches(merchantId, params), "/branches");
    }

    /**
     * Get a single branch by ID.
     */
    @GetMapping("/{id}")
    public ApiResponse<BranchResponse> get(@PathVariable Long id) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        BranchResponse branch = branchService.getBranch(id, merchantId);
        if (branch == null) {
            return ApiResponse.error(404, "Branch not found");
        }
        return ApiResponse.success(branch);
    }

    /**
     * Create a new branch.
     */
    @PostMapping
    public ApiResponse<BranchResponse> create(@Valid @RequestBody BranchCreateRequest request) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        BranchResponse branch = branchService.createBranch(merchantId, request);
        return ApiResponse.success(branch, "Branch created successfully");
    }

    /**
     * Update an existing branch.
     */
    @PutMapping("/{id}")
    public ApiResponse<BranchResponse> update(@PathVariable Long id, @Valid @RequestBody BranchUpdateRequest request) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        BranchResponse branch = branchService.updateBranch(id, merchantId, request);
        if (branch == null) {
            return ApiResponse.error(404, "Branch not found");
        }
        return ApiResponse.success(branch, "Branch updated successfully");
    }

    /**
     * Set a branch as primary.
     */
    @PostMapping("/{id}/set-primary")
    public ApiResponse<BranchResponse> setPrimary(@PathVariable Long id) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        BranchResponse branch = branchService.setPrimary(id, merchantId);
        if (branch == null) {
            return ApiResponse.error(404, "Branch not found");
        }
        return ApiResponse.success(branch, "Branch set as primary");
    }

    /**
     * Delete a branch.
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        boolean deleted = branchService.deleteBranch(id, merchantId);
        if (!deleted) {
            return ApiResponse.error(404, "Branch not found or cannot be deleted");
        }
        return ApiResponse.success(null, "Branch deleted successfully");
    }

    /**
     * Get branch statistics (terminal count, transaction volume, etc.).
     */
    @GetMapping("/{id}/stats")
    public ApiResponse<Map<String, Object>> stats(@PathVariable Long id) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        Map<String, Object> stats = branchService.getBranchStats(id, merchantId);
        if (stats == null) {
            return ApiResponse.error(404, "Branch not found");
        }
        return ApiResponse.success(stats);
    }

    /**
     * Update branch settlement account.
     */
    @PutMapping("/{id}/settlement-account")
    public ApiResponse<BranchResponse> updateSettlementAccount(@PathVariable Long id,
            @Valid @RequestBody BranchSettlementRequest request) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        BranchResponse branch = branchService.updateSettlementAccount(id, merchantId, request.getAccountNumber(),
                request.getAccountName(), request.getBankCode(), request.getBankName());
        if (branch == null) {
            return ApiResponse.error(404, "Branch not found");
        }
        return ApiResponse.success(branch, "Settlement account updated successfully");
    }

    /**
     * Clear branch settlement account (use merchant default).
     */
    @DeleteMapping("/{id}/settlement-account")
    public ApiResponse<BranchResponse> clearSettlementAccount(@PathVariable Long id) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        BranchResponse branch = branchService.clearSettlementAccount(id, merchantId);
        if (branch == null) {
            return ApiResponse.error(404, "Branch not found");
        }
        return ApiResponse.success(branch, "Settlement account cleared successfully");
    }
}

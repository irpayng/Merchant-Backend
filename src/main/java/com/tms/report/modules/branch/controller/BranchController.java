package com.tms.report.modules.branch.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.branch.dto.BranchCreateRequest;
import com.tms.report.modules.branch.dto.BranchResponse;
import com.tms.report.modules.branch.dto.BranchSettlementRequest;
import com.tms.report.modules.branch.dto.BranchUpdateRequest;
import com.tms.report.modules.branch.service.BranchService;
import com.tms.report.modules.terminal.model.Terminal;
import jakarta.validation.Valid;
import java.util.List;
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
     * Assign a settlement account to a branch. The account must be from the
     * merchant's settlement account pool.
     */
    @PutMapping("/{id}/settlement-account")
    public ApiResponse<BranchResponse> assignSettlementAccount(@PathVariable Long id,
            @Valid @RequestBody BranchSettlementRequest request) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        BranchResponse branch = branchService.assignSettlementAccount(id, merchantId, request.getSettlementAccountId());
        if (branch == null) {
            return ApiResponse.error(404, "Branch not found or settlement account not valid");
        }
        return ApiResponse.success(branch, "Settlement account assigned successfully");
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

    // ────────────────────────────────────────────────────────────── Terminal
    // Assignment

    /**
     * List terminals assigned to a branch.
     */
    @GetMapping("/{id}/terminals")
    public Map<String, Object> listTerminals(@PathVariable Long id, @RequestParam Map<String, String> params) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return PagedResponse.empty("/branches/" + id + "/terminals");
        }
        return branchService.listBranchTerminals(id, merchantId, params);
    }

    /**
     * Get terminal stats for a branch.
     */
    @GetMapping("/{id}/terminals/stats")
    public ApiResponse<Map<String, Object>> terminalStats(@PathVariable Long id) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        Map<String, Object> stats = branchService.getBranchTerminalStats(id, merchantId);
        if (stats == null) {
            return ApiResponse.error(404, "Branch not found");
        }
        return ApiResponse.success(stats);
    }

    /**
     * Assign a terminal to a branch.
     */
    @PostMapping("/{id}/terminals/{terminalId}")
    public ApiResponse<Terminal> assignTerminal(@PathVariable Long id, @PathVariable Long terminalId) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        Terminal terminal = branchService.assignTerminalToBranch(id, terminalId, merchantId);
        if (terminal == null) {
            return ApiResponse.error(404, "Branch or terminal not found");
        }
        return ApiResponse.success(terminal, "Terminal assigned to branch");
    }

    /**
     * Unassign a terminal from a branch.
     */
    @DeleteMapping("/{id}/terminals/{terminalId}")
    public ApiResponse<Terminal> unassignTerminal(@PathVariable Long id, @PathVariable Long terminalId) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        Terminal terminal = branchService.unassignTerminalFromBranch(id, terminalId, merchantId);
        if (terminal == null) {
            return ApiResponse.error(404, "Branch or terminal not found");
        }
        return ApiResponse.success(terminal, "Terminal unassigned from branch");
    }

    /**
     * List terminals available for assignment (not yet assigned to any branch).
     */
    @GetMapping("/{id}/terminals/available")
    public ApiResponse<List<Terminal>> availableTerminals(@PathVariable Long id) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        List<Terminal> terminals = branchService.getAvailableTerminals(merchantId);
        return ApiResponse.success(terminals);
    }

    // ────────────────────────────────────────────────────────────── Branch
    // Transactions

    /**
     * List transactions for terminals assigned to a branch.
     */
    @GetMapping("/{id}/transactions")
    public Map<String, Object> listTransactions(@PathVariable Long id, @RequestParam Map<String, String> params) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return PagedResponse.empty("/branches/" + id + "/transactions");
        }
        return branchService.listBranchTransactions(id, merchantId, params);
    }
}

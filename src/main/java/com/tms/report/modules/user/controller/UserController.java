package com.tms.report.modules.user.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.export.CsvExporter;
import com.tms.report.core.export.XlsxExporter;
import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.modules.transaction.service.TransactionService;
import com.tms.report.modules.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final TransactionService transactionService;
    private final com.tms.report.modules.grpc.service.GrpcClient grpcClient;
    private final com.tms.report.modules.terminal.repository.TerminalRepository terminalRepository;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        Map<String, Object> extra = new LinkedHashMap<>();
        try {
            extra.put("filters", userService.getFilters());
            extra.put("stats", userService.getSummary());
        } catch (Exception e) {
            extra.put("filters", Map.of());
            extra.put("stats", Map.of());
        }

        return PagedResponse.from(userService.index(params), "/users", extra);
    }

    @GetMapping("/download")
    public void download(@RequestParam Map<String, String> params, HttpServletResponse response) throws Exception {
        XlsxExporter.streamPaged(response, "users",
                new String[]{"ID", "Name", "Email", "Phone Number", "Type", "Tier", "Active", "Created At"}, 1000,
                (page, size) -> userService.index(QueryFilterHelper.pageParams(params, page, size)).getContent(),
                row -> new String[]{String.valueOf(row.getId()), row.getName(), row.getEmail(), row.getPhoneNumber(),
                        row.getType(), row.getTier() != null ? row.getTier().getName() : "",
                        row.getActive() != null ? row.getActive().toString() : "",
                        row.getCreatedAt() != null ? row.getCreatedAt().toString() : ""});
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> show(@PathVariable Long id) {
        return ApiResponse.success(userService.showDetail(id));
    }

    @GetMapping("/{id}/transactions")
    public Map<String, Object> userTransactions(@PathVariable Long id, @RequestParam Map<String, String> params,
            HttpServletRequest request) {
        params.put("user_id", id.toString());
        extractDates(request, params);
        return PagedResponse.from(transactionService.index(params), "/users/" + id + "/transactions",
                Map.of("filters", transactionService.filters(), "stats", transactionService.getSummary(params)));
    }

    @GetMapping("/{id}/transactions/download")
    public void downloadTransactions(@PathVariable Long id, @RequestParam Map<String, String> params,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        params.put("user_id", id.toString());
        extractDates(request, params);
        XlsxExporter.streamPaged(response, "user-transactions",
                new String[]{"Reference", "Amount", "Product", "Provider", "Channel", "Status", "Date"}, 1000,
                (page, size) -> transactionService.index(QueryFilterHelper.pageParams(params, page, size)).getContent(),
                row -> new String[]{row.getReference(), row.getAmount(),
                        row.getProduct() != null ? row.getProduct().getName() : "",
                        row.getProvider() != null ? row.getProvider().getName() : "",
                        row.getChannel() != null ? row.getChannel().getName() : "",
                        row.getStatus() != null ? row.getStatus().getName() : "",
                        row.getCreatedAt() != null ? row.getCreatedAt().toString() : ""});
    }

    @GetMapping("/{id}/wallets")
    public Map<String, Object> userWallets(@PathVariable Long id, @RequestParam Map<String, String> params) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("filters", Map.of("types", List.of("default", "commission")));
        extra.put("stats", userService.getUserWalletStats(id, params));
        return PagedResponse.from(userService.getUserWallets(id, params), "/users/" + id + "/wallets", extra);
    }

    @GetMapping("/{id}/statements")
    public Map<String, Object> userStatements(@PathVariable Long id, @RequestParam Map<String, String> params,
            HttpServletRequest request) {
        extractDates(request, params);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("filters", Map.of("type", List.of("credit", "debit"), "owner_type", List.of("users", "providers")));
        extra.put("stats", userService.getUserStatementStats(id, params));
        return PagedResponse.from(userService.getUserStatements(id, params), "/users/" + id + "/statements", extra);
    }

    @GetMapping("/{id}/statements/download")
    public void downloadStatements(@PathVariable Long id, @RequestParam Map<String, String> params,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        extractDates(request, params);
        XlsxExporter.streamPaged(response, "user-statements", new String[]{
                "ID", "Type", "Amount", "Description", "Previous Balance", "Current Balance", "Wallet Type", "Date"},
                1000,
                (page, size) -> userService.getUserStatements(id, QueryFilterHelper.pageParams(params, page, size))
                        .getContent(),
                row -> new String[]{String.valueOf(row.get("id")), String.valueOf(row.getOrDefault("type", "")),
                        CsvExporter.formatCurrency(row.get("amount")),
                        String.valueOf(row.getOrDefault("description", "")),
                        CsvExporter.formatCurrency(row.get("previous_balance")),
                        CsvExporter.formatCurrency(row.get("current_balance")),
                        String.valueOf(row.getOrDefault("wallet_type", "")),
                        row.get("created_at") != null ? row.get("created_at").toString() : ""});
    }

    private void extractDates(HttpServletRequest request, Map<String, String> params) {
        String[] dates = request.getParameterValues("dates[]");
        if (dates != null && dates.length >= 2) {
            params.put("dates[0]", dates[0]);
            params.put("dates[1]", dates[1]);
        }
    }

    @GetMapping("/filters")
    public ApiResponse<Map<String, Object>> getFilters() {
        return ApiResponse.success(userService.getFilters());
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> getSummary() {
        return ApiResponse.success(userService.getSummary());
    }

    @GetMapping("/stats/agents")
    public ApiResponse<Map<String, Object>> agentStats() {
        return ApiResponse.success(userService.agentStats());
    }

    @GetMapping("/top-agents")
    public ApiResponse<List<Map<String, Object>>> topAgents(@RequestParam Map<String, String> params) {
        return ApiResponse.success(userService.getTopPerformingAgents(params));
    }

    @GetMapping("/aggregator-associations")
    public Map<String, Object> aggregatorAssociations(@RequestParam Map<String, String> params) {
        return PagedResponse.from(userService.getAggregatorAssociations(params), "/users/aggregator-associations",
                Map.of("filters", userService.getAggregatorAssociationFilters()));
    }

    @PostMapping("/{id}/associate-parent")
    public ApiResponse<Map<String, Object>> associateParent(@PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        long parentId = body.containsKey("parent_id") ? Long.parseLong(body.get("parent_id").toString()) : 0;
        return ApiResponse.success(requireSuccess(grpcClient.associateParent(id, parentId)));
    }

    @DeleteMapping("/{id}/associate-parent")
    public ApiResponse<Map<String, Object>> removeParent(@PathVariable Long id) {
        return ApiResponse.success(requireSuccess(grpcClient.associateParent(id, 0)));
    }

    /**
     * Unwrap a gRPC command envelope and convert a {@code success=false} response
     * (e.g. user-service rejecting the association because an aggregator's parent
     * must be a super aggregator) into a 400 so the global handler renders it as an
     * error toast. Without this, a rejected command was returned as an HTTP 200
     * "success" and the admin UI showed a green toast while nothing changed.
     */
    private Map<String, Object> requireSuccess(Map<String, Object> result) {
        if (Boolean.FALSE.equals(result.get("success"))) {
            Object msg = result.get("message");
            throw new IllegalArgumentException(msg != null ? msg.toString() : "Operation failed");
        }
        return result;
    }

    @GetMapping("/aggregators/list")
    public ApiResponse<List<Map<String, Object>>> listAggregators(@RequestParam(required = false) String search) {
        return ApiResponse.success(userService.listAggregators(search));
    }

    /**
     * GET /users/{id}/terminals — POS devices provisioned to this user. Powers the
     * "Devices" tab on the user-detail page.
     */
    @GetMapping("/{id}/terminals")
    public ApiResponse<List<com.tms.report.modules.terminal.model.Terminal>> userTerminals(@PathVariable Long id) {
        return ApiResponse.success(terminalRepository.findByUserId(id));
    }

    /**
     * Revoke a user's agent or merchant status — demotes them back to a regular
     * user. The previous role is recorded in the activity log.
     */
    @com.tms.report.modules.activity.annotation.LogActivity(action = "revokeRole", description = "{admin} revoked the {body.type} role from {user}", userFrom = "entity:User")
    @PatchMapping("/{id}/revoke-role")
    public ApiResponse<Map<String, Object>> revokeRole(@PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        // Body is optional; we don't require a specific "type" to unmake — the
        // microservice simply sets type = "user".
        return ApiResponse.success(grpcClient.updateUserType(id, "user"));
    }

    /**
     * Promote a user to aggregator. Once flipped, the system recognises them as an
     * aggregator regardless of whether they currently have downliners — the type
     * alone gates aggregator-only privileges (target rules, dashboard scopes,
     * commission tier resolution). Actual commissions still depend on downliner
     * activity. Existing downliners (users whose {@code parent_id} already pointed
     * at this user) are unchanged.
     *
     * <p>
     * The user microservice rejects the promotion if the user is not on Tier 3 —
     * the platform's commission and target rules assume the upper-tier limits, so
     * promoting a Tier 1 / Tier 2 user would create one with caps they cannot
     * finance. Surface that as a 400 to the admin UI.
     */
    @com.tms.report.modules.activity.annotation.LogActivity(action = "makeAggregator", description = "{admin} made {user} an aggregator", userFrom = "entity:User")
    @PatchMapping("/{id}/make-aggregator")
    public ApiResponse<Map<String, Object>> makeAggregator(@PathVariable Long id) {
        return ApiResponse.success(promoteUserType(id, "aggregator"));
    }

    /**
     * Promote a user to super aggregator. Behaves like {@link #makeAggregator} for
     * downliner-binding purposes — both types count as aggregator-class users — but
     * the type string is preserved for commission tier resolution elsewhere in the
     * platform.
     */
    @com.tms.report.modules.activity.annotation.LogActivity(action = "makeSuperAggregator", description = "{admin} made {user} a super aggregator", userFrom = "entity:User")
    @PatchMapping("/{id}/make-super-aggregator")
    public ApiResponse<Map<String, Object>> makeSuperAggregator(@PathVariable Long id) {
        return ApiResponse.success(promoteUserType(id, "super_aggregator"));
    }

    /**
     * Shared promotion path. Unwraps the gRPC envelope and converts a
     * {@code success=false} response (e.g. tier guard rejection from user-service)
     * into a 400 so the global handler renders it as a toast.
     */
    private Map<String, Object> promoteUserType(Long id, String type) {
        return requireSuccess(grpcClient.updateUserType(id, type));
    }

    /**
     * Suspend a user account (admin/compliance). A staff-imposed lock distinct from
     * the self-liftable freeze — the suspended user sees a non-dismissible lock
     * screen on the mobile/aggregator app and cannot transact until reinstated.
     */
    @com.tms.report.modules.activity.annotation.LogActivity(action = "suspend", description = "{admin} suspended {user}", userFrom = "entity:User")
    @PatchMapping("/{id}/suspend")
    public ApiResponse<Map<String, Object>> suspend(@PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", null) : null;
        return ApiResponse.success(requireSuccess(grpcClient.suspendUser(id, reason)));
    }

    /**
     * Reinstate a suspended user account (admin/compliance). An admin can lift any
     * suspension, including those placed by an aggregator.
     */
    @com.tms.report.modules.activity.annotation.LogActivity(action = "unsuspend", description = "{admin} reinstated {user}", userFrom = "entity:User")
    @PatchMapping("/{id}/unsuspend")
    public ApiResponse<Map<String, Object>> unsuspend(@PathVariable Long id) {
        return ApiResponse.success(requireSuccess(grpcClient.unsuspendUser(id)));
    }

    /**
     * Block a user account (admin/compliance). The hardest lock — a blocked user is
     * rejected outright at sign-in (no token issued) and every existing session is
     * revoked, so they cannot reach any authenticated surface until an admin
     * unblocks them. Stronger than a suspension, which still issues a token for the
     * in-app "contact support" CTA.
     */
    @com.tms.report.modules.activity.annotation.LogActivity(action = "block", description = "{admin} blocked {user}", userFrom = "entity:User")
    @PatchMapping("/{id}/block")
    public ApiResponse<Map<String, Object>> block(@PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", null) : null;
        return ApiResponse.success(requireSuccess(grpcClient.blockUser(id, reason)));
    }

    /** Unblock a user account (admin/compliance) so they can sign in again. */
    @com.tms.report.modules.activity.annotation.LogActivity(action = "unblock", description = "{admin} unblocked {user}", userFrom = "entity:User")
    @PatchMapping("/{id}/unblock")
    public ApiResponse<Map<String, Object>> unblock(@PathVariable Long id) {
        return ApiResponse.success(requireSuccess(grpcClient.unblockUser(id)));
    }

    /**
     * Unfreeze a user account (admin/compliance). Staff override of the
     * self-service liveness unfreeze — for users who can't pass face verification
     * (no BVN photo, support case). Also resets the PIN failed-attempt streak.
     */
    @com.tms.report.modules.activity.annotation.LogActivity(action = "unfreeze", description = "{admin} unfroze {user}", userFrom = "entity:User")
    @PatchMapping("/{id}/unfreeze")
    public ApiResponse<Map<String, Object>> unfreeze(@PathVariable Long id) {
        return ApiResponse.success(requireSuccess(grpcClient.unfreezeUser(id)));
    }

    /**
     * Reset a user's bound device (admin/compliance). Clears the device pointers
     * and revokes sessions so the user can sign in on a new handset without the
     * device-change liveness challenge — used when a phone is lost or replaced.
     */
    @com.tms.report.modules.activity.annotation.LogActivity(action = "resetDevice", description = "{admin} reset device for {user}", userFrom = "entity:User")
    @PatchMapping("/{id}/reset-device")
    public ApiResponse<Map<String, Object>> resetDevice(@PathVariable Long id) {
        return ApiResponse.success(requireSuccess(grpcClient.resetDevice(id)));
    }

    /**
     * Remove a user's temporary new-device transaction limit (admin/compliance).
     * Clears only the device-change timestamps so the ₦20,000/day cap applied on
     * the day a device changed is lifted. Unlike reset-device, the bound device and
     * active sessions are left intact — the user stays logged in on their current
     * handset and only the temporary limit is removed.
     */
    @com.tms.report.modules.activity.annotation.LogActivity(action = "clearNewDeviceLimit", description = "{admin} removed new-device limit for {user}", userFrom = "entity:User")
    @PatchMapping("/{id}/clear-new-device-limit")
    public ApiResponse<Map<String, Object>> clearNewDeviceLimit(@PathVariable Long id) {
        return ApiResponse.success(requireSuccess(grpcClient.clearNewDeviceLimit(id)));
    }
}

package com.tms.report.modules.grpc.service;

import com.shared.util.Ulid;
import com.tms.report.core.security.MerchantUserDetails;
import com.tms.report.grpc.config.CheckInstantSettlementRequest;
import com.tms.report.grpc.config.ConfigCommandResponse;
import com.tms.report.grpc.config.ConfigServiceGrpc;
import com.tms.report.grpc.config.CreateConfigurationRequest;
import com.tms.report.grpc.config.CreateFundingAlertRequest;
import com.tms.report.grpc.config.CreateTerminalRequest;
import com.tms.report.grpc.config.CreateTerminalsBatchRequest;
import com.tms.report.grpc.config.CreateTidRequest;
import com.tms.report.grpc.config.DataPlanRequest;
import com.tms.report.grpc.config.DeleteConfigurationRequest;
import com.tms.report.grpc.config.DeleteDataPlanRequest;
import com.tms.report.grpc.config.DeleteFundingAlertRequest;
import com.tms.report.grpc.config.DeleteTidRequest;
import com.tms.report.grpc.config.EnrollInstantSettlementRequest;
import com.tms.report.grpc.config.GetAllProviderBalancesRequest;
import com.tms.report.grpc.config.GetConfigValueRequest;
import com.tms.report.grpc.config.GetProviderBalanceRequest;
import com.tms.report.grpc.config.ImportDataPlansRequest;
import com.tms.report.grpc.config.MapTerminalRequest;
import com.tms.report.grpc.config.ReactivateInstantSettlementRequest;
import com.tms.report.grpc.config.RefreshProviderBalanceRequest;
import com.tms.report.grpc.config.RefreshProviderCardKeysRequest;
import com.tms.report.grpc.config.RemoveMaxBalanceRequest;
import com.tms.report.grpc.config.RevokeInstantSettlementRequest;
import com.tms.report.grpc.config.SetMaxBalanceRequest;
import com.tms.report.grpc.config.SuspendInstantSettlementRequest;
import com.tms.report.grpc.config.TerminalEntry;
import com.tms.report.grpc.config.UnmapTerminalRequest;
import com.tms.report.grpc.config.UpdateConfigurationRequest;
import com.tms.report.grpc.config.UpdateFundingAlertRequest;
import com.tms.report.grpc.config.UpdateSettlementWindowRequest;
import com.tms.report.grpc.config.UpdateTerminalRequest;
import com.tms.report.grpc.config.UpdateTidRequest;
import com.tms.report.grpc.dispute.Actor;
import com.tms.report.grpc.dispute.AddConversationRequest;
import com.tms.report.grpc.dispute.CloseDisputeRequest;
import com.tms.report.grpc.dispute.CreateDisputeRequest;
import com.tms.report.grpc.dispute.DisputeServiceGrpc;
import com.tms.report.grpc.dispute.UpdateDisputeRequest;
import com.tms.report.grpc.kyc.ApproveAddressRequest;
import com.tms.report.grpc.kyc.ApproveBusinessApplicationRequest;
import com.tms.report.grpc.kyc.ApproveDocumentRequest;
import com.tms.report.grpc.kyc.ApproveVerificationRequest;
import com.tms.report.grpc.kyc.KycServiceGrpc;
import com.tms.report.grpc.kyc.RejectAddressRequest;
import com.tms.report.grpc.kyc.RejectBusinessApplicationRequest;
import com.tms.report.grpc.kyc.RejectDocumentRequest;
import com.tms.report.grpc.kyc.RejectVerificationRequest;
import com.tms.report.grpc.kyc.UpdateAddressRequest;
import com.tms.report.grpc.ledger.ClosePeriodRequest;
import com.tms.report.grpc.ledger.DetectDiscrepanciesRequest;
import com.tms.report.grpc.ledger.DiscrepanciesResponse;
import com.tms.report.grpc.ledger.FixDiscrepancyRequest;
import com.tms.report.grpc.ledger.LandingEntryItem;
import com.tms.report.grpc.ledger.LedgerCommandResponse;
import com.tms.report.grpc.ledger.LedgerServiceGrpc;
import com.tms.report.grpc.ledger.ListPeriodClosesRequest;
import com.tms.report.grpc.ledger.PeriodCloseRecord;
import com.tms.report.grpc.ledger.PeriodCloseResponse;
import com.tms.report.grpc.ledger.PeriodClosesResponse;
import com.tms.report.grpc.ledger.RecordSettlementLandingRequest;
import com.tms.report.grpc.ledger.RecordSettlementLandingsRequest;
import com.tms.report.grpc.notification.NotificationCommandResponse;
import com.tms.report.grpc.notification.NotificationServiceGrpc;
import com.tms.report.grpc.notification.SendEmailRequest;
import com.tms.report.grpc.notification.SendNotificationCommandRequest;
import com.tms.report.grpc.notification.SendSmsRequest;
import com.tms.report.grpc.settlement.CreateSettlementRequest;
import com.tms.report.grpc.settlement.SettlementServiceGrpc;
import com.tms.report.grpc.transaction.BulkPaymentRow;
import com.tms.report.grpc.transaction.CreateAdminTransferRequest;
import com.tms.report.grpc.transaction.CreateBulkPaymentRequest;
import com.tms.report.grpc.transaction.CreateReconciliationConfigRequest;
import com.tms.report.grpc.transaction.CreateReconciliationUploadRequest;
import com.tms.report.grpc.transaction.DeleteReconciliationConfigRequest;
import com.tms.report.grpc.transaction.DisburseBulkPaymentRequest;
import com.tms.report.grpc.transaction.MarkTransactionRequest;
import com.tms.report.grpc.transaction.NameEnquiryGrpcRequest;
import com.tms.report.grpc.transaction.ReconcileItemRequest;
import com.tms.report.grpc.transaction.ReconcileUploadRequest;
import com.tms.report.grpc.transaction.ReplayVirtualFundingRequest;
import com.tms.report.grpc.transaction.ReprocessBulkPaymentItemRequest;
import com.tms.report.grpc.transaction.RequeryProviderRequest;
import com.tms.report.grpc.transaction.TransactionServiceGrpc;
import com.tms.report.grpc.transaction.UpdateReconciliationConfigRequest;
import com.tms.report.grpc.user.CreateUserCommandRequest;
import com.tms.report.grpc.user.InviteUserRequest;
import com.tms.report.grpc.user.UpdateUserCommandRequest;
import com.tms.report.grpc.user.UserCommandResponse;
import com.tms.report.grpc.user.UserServiceGrpc;
import com.tms.report.grpc.wallet.ApproveManualFundingRequest;
import com.tms.report.grpc.wallet.CreateCommissionTransferRequest;
import com.tms.report.grpc.wallet.CreateManualFundingRequest;
import com.tms.report.grpc.wallet.RejectManualFundingRequest;
import com.tms.report.grpc.wallet.SetPndRequest;
import com.tms.report.grpc.wallet.WalletCommandResponse;
import com.tms.report.grpc.wallet.WalletServiceGrpc;
import com.tms.report.modules.grpc.config.GrpcProperties;
import com.tms.report.modules.grpc.exception.GrpcException;
import com.tms.report.modules.merchantuser.model.MerchantUser;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * gRPC client for communicating with microservices. Replaces the previous
 * HTTP-based client that posted to tms-api /grpc/command/{method}. Now uses
 * real gRPC stubs to call the appropriate microservice directly.
 *
 * Public method signatures are preserved for backward compatibility with
 * controllers.
 */
@Slf4j
@Service
public class GrpcClient {

    private final GrpcProperties props;
    private final UserServiceGrpc.UserServiceBlockingStub userStub;
    private final WalletServiceGrpc.WalletServiceBlockingStub walletStub;
    private final ConfigServiceGrpc.ConfigServiceBlockingStub configStub;
    private final TransactionServiceGrpc.TransactionServiceBlockingStub transactionStub;
    private final NotificationServiceGrpc.NotificationServiceBlockingStub notificationStub;
    private final LedgerServiceGrpc.LedgerServiceBlockingStub ledgerStub;
    private final SettlementServiceGrpc.SettlementServiceBlockingStub settlementStub;
    private final KycServiceGrpc.KycServiceBlockingStub kycStub;
    private final DisputeServiceGrpc.DisputeServiceBlockingStub disputeStub;

    public GrpcClient(GrpcProperties props, UserServiceGrpc.UserServiceBlockingStub userStub,
            WalletServiceGrpc.WalletServiceBlockingStub walletStub,
            ConfigServiceGrpc.ConfigServiceBlockingStub configStub,
            TransactionServiceGrpc.TransactionServiceBlockingStub transactionStub,
            NotificationServiceGrpc.NotificationServiceBlockingStub notificationStub,
            LedgerServiceGrpc.LedgerServiceBlockingStub ledgerStub,
            SettlementServiceGrpc.SettlementServiceBlockingStub settlementStub,
            KycServiceGrpc.KycServiceBlockingStub kycStub, DisputeServiceGrpc.DisputeServiceBlockingStub disputeStub) {
        this.props = props;
        this.userStub = userStub;
        this.walletStub = walletStub;
        this.configStub = configStub;
        this.transactionStub = transactionStub;
        this.notificationStub = notificationStub;
        this.ledgerStub = ledgerStub;
        this.settlementStub = settlementStub;
        this.kycStub = kycStub;
        this.disputeStub = disputeStub;
    }

    // ── User commands → user-service ──

    public Map<String, Object> createUser(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("CreateUser", ref);
        try {
            var actor = buildUserActor();
            var builder = CreateUserCommandRequest.newBuilder().setReference(ref).setActor(actor);
            if (data.containsKey("email"))
                builder.setEmail(str(data, "email"));
            if (data.containsKey("phone_number"))
                builder.setPhoneNumber(str(data, "phone_number"));
            if (data.containsKey("password"))
                builder.setPassword(str(data, "password"));
            if (data.containsKey("type"))
                builder.setType(str(data, "type"));

            UserCommandResponse resp = userStub.createUser(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateUser", ref, e);
        }
    }

    public Map<String, Object> updateUser(String userId, Map<String, Object> data) {
        String ref = ref(data);
        logRequest("UpdateUser", ref);
        try {
            var actor = buildUserActor();
            var builder = UpdateUserCommandRequest.newBuilder().setReference(ref).setUserId(userId).setActor(actor);
            if (data.containsKey("email"))
                builder.setEmail(str(data, "email"));
            if (data.containsKey("phone_number"))
                builder.setPhoneNumber(str(data, "phone_number"));
            if (data.containsKey("is_active"))
                builder.setIsActive((Boolean) data.get("is_active"));
            if (data.containsKey("type"))
                builder.setType(str(data, "type"));

            UserCommandResponse resp = userStub.updateUser(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateUser", ref, e);
        }
    }

    public Map<String, Object> inviteUser(List<String> emails, List<String> phoneNumbers) {
        String ref = Ulid.generate();
        logRequest("InviteUser", ref);
        try {
            var resp = userStub.inviteUser(InviteUserRequest.newBuilder().setReference(ref).addAllEmails(emails)
                    .addAllPhoneNumbers(phoneNumbers).setActor(buildUserActor()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("InviteUser", ref, e);
        }
    }

    public Map<String, Object> associateParent(long userId, long parentId) {
        String ref = Ulid.generate();
        logRequest("AssociateParent", ref);
        try {
            var builder = com.tms.report.grpc.user.AssociateParentRequest.newBuilder().setReference(ref)
                    .setUserId(userId).setParentId(parentId).setActor(buildUserActor());

            UserCommandResponse resp = userStub.associateParent(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("AssociateParent", ref, e);
        }
    }

    /**
     * Decommission an aggregator: flip every downstream agent to a chosen
     * replacement (or unassign with replacementId=0), demote the old aggregator's
     * type to 'user'. Wallet PND on the old aggregator is applied separately by
     * target-service.
     */
    public Map<String, Object> decommissionAggregator(long oldAggregatorId, long replacementAggregatorId,
            String reason) {
        String ref = Ulid.generate();
        logRequest("DecommissionAggregator", ref);
        try {
            var builder = com.tms.report.grpc.user.DecommissionAggregatorRequest.newBuilder().setReference(ref)
                    .setOldAggregatorId(oldAggregatorId).setReplacementAggregatorId(replacementAggregatorId)
                    .setReason(reason == null ? "admin_decommission" : reason).setActor(buildUserActor());
            var resp = userStub.decommissionAggregator(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("DecommissionAggregator", ref, e);
        }
    }

    /**
     * Mark an off-app account-deletion request (submitted via
     * {@code https://irpay.ng/account-deletion}) as processed by support.
     * Idempotent on the user-service side.
     */
    public Map<String, Object> markAccountDeletionRequestProcessed(long requestId, String note) {
        String ref = Ulid.generate();
        logRequest("MarkAccountDeletionRequestProcessed", ref);
        try {
            var builder = com.tms.report.grpc.user.MarkAccountDeletionRequestProcessedRequest.newBuilder()
                    .setReference(ref).setRequestId(requestId).setActor(buildUserActor());
            if (note != null && !note.isBlank()) {
                builder.setNote(note);
            }
            UserCommandResponse resp = userStub.markAccountDeletionRequestProcessed(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("MarkAccountDeletionRequestProcessed", ref, e);
        }
    }

    /**
     * Admin override — set a user's type directly. Used for revoking agent or
     * merchant status (pass {@code "user"}) and nothing else today.
     */
    public Map<String, Object> updateUserType(long userId, String type) {
        String ref = Ulid.generate();
        logRequest("UpdateUserType", ref);
        try {
            var resp = userStub.updateUserType(com.tms.report.grpc.user.UpdateUserTypeRequest.newBuilder()
                    .setUserId(userId).setType(type).build());
            Map<String, Object> result = new HashMap<>();
            result.put("success", resp.getSuccess());
            result.put("reference", ref);
            result.put("message", resp.getMessage());
            return result;
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateUserType", ref, e);
        }
    }

    /**
     * Authenticate a user by email/phone + password against tms-user. Used for
     * cross-system login: mobile-upgraded merchants can sign into the merchant
     * dashboard using their tms-user credentials.
     *
     * @param identifier
     *            email or phone number
     * @param password
     *            plain-text password
     * @return map with success, reason, message, and on success: user_id, type,
     *         email, phone_number, first_name, last_name, business_name
     */
    public Map<String, Object> authUser(String identifier, String password) {
        logRequest("AuthUser", identifier);
        try {
            var resp = userStub.authUser(com.tms.report.grpc.user.AuthUserRequest.newBuilder().setIdentifier(identifier)
                    .setPassword(password).build());
            Map<String, Object> result = new HashMap<>();
            result.put("success", resp.getSuccess());
            result.put("reason", resp.getReason());
            result.put("message", resp.getMessage());
            if (resp.getSuccess()) {
                result.put("user_id", resp.getUserId());
                result.put("type", resp.getType());
                result.put("email", resp.getEmail());
                result.put("phone_number", resp.getPhoneNumber());
                result.put("first_name", resp.getFirstName());
                result.put("last_name", resp.getLastName());
                result.put("business_name", resp.getBusinessName());
            }
            return result;
        } catch (StatusRuntimeException e) {
            throw grpcError("AuthUser", identifier, e);
        }
    }

    /**
     * Authenticate an operator by email + password for dashboard login. Returns
     * operator profile and merchant_user_id so the caller can look up roles.
     */
    public Map<String, Object> authOperator(String email, String password) {
        logRequest("AuthOperator", email);
        try {
            var resp = userStub.authOperator(com.tms.report.grpc.user.AuthOperatorRequest.newBuilder().setEmail(email)
                    .setPassword(password).build());
            Map<String, Object> result = new HashMap<>();
            result.put("success", resp.getSuccess());
            result.put("reason", resp.getReason());
            result.put("message", resp.getMessage());
            if (resp.getSuccess()) {
                result.put("operator_id", resp.getOperatorId());
                result.put("merchant_user_id", resp.getMerchantUserId());
                result.put("name", resp.getName());
                result.put("email", resp.getEmail());
                result.put("phone_number", resp.getPhoneNumber());
                result.put("username", resp.getUsername());
            }
            return result;
        } catch (StatusRuntimeException e) {
            throw grpcError("AuthOperator", email, e);
        }
    }

    /**
     * Create an operator (staff) under a merchant. Called when a merchant invites
     * staff via the dashboard.
     */
    public Map<String, Object> createOperator(long merchantUserId, String username, String password, String name,
            String email, String phoneNumber, String pin, boolean dashboardEnabled, boolean posEnabled) {
        logRequest("CreateOperator", username);
        try {
            var builder = com.tms.report.grpc.user.CreateOperatorRequest.newBuilder().setMerchantUserId(merchantUserId)
                    .setUsername(username).setPassword(password).setPin(pin).setDashboardEnabled(dashboardEnabled)
                    .setPosEnabled(posEnabled);
            if (name != null)
                builder.setName(name);
            if (email != null)
                builder.setEmail(email);
            if (phoneNumber != null)
                builder.setPhoneNumber(phoneNumber);

            var resp = userStub.createOperator(builder.build());
            Map<String, Object> result = new HashMap<>();
            result.put("success", resp.getSuccess());
            result.put("reason", resp.getReason());
            result.put("message", resp.getMessage());
            if (resp.getSuccess()) {
                result.put("operator_id", resp.getOperatorId());
            }
            return result;
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateOperator", username, e);
        }
    }

    /**
     * Update an operator's status, password, or access flags.
     */
    public Map<String, Object> updateOperator(long operatorId, long merchantUserId, String status, String password,
            Boolean dashboardEnabled, Boolean posEnabled) {
        logRequest("UpdateOperator", String.valueOf(operatorId));
        try {
            var builder = com.tms.report.grpc.user.UpdateOperatorRequest.newBuilder().setOperatorId(operatorId)
                    .setMerchantUserId(merchantUserId);

            if (status != null) {
                builder.setUpdateStatus(true).setStatus(status);
            }
            if (password != null) {
                builder.setUpdatePassword(true).setPassword(password);
            }
            if (dashboardEnabled != null) {
                builder.setUpdateDashboardEnabled(true).setDashboardEnabled(dashboardEnabled);
            }
            if (posEnabled != null) {
                builder.setUpdatePosEnabled(true).setPosEnabled(posEnabled);
            }

            var resp = userStub.updateOperator(builder.build());
            Map<String, Object> result = new HashMap<>();
            result.put("success", resp.getSuccess());
            result.put("reason", resp.getReason());
            result.put("message", resp.getMessage());
            return result;
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateOperator", String.valueOf(operatorId), e);
        }
    }

    /**
     * Set a user's password in tms-user. Used by password reset and activation
     * flows to keep credentials in sync between Merchant-Backend and tms-user. This
     * enables TID-uploaded merchants to log into both the merchant dashboard and
     * POS terminal using the same credentials.
     *
     * @param userId
     *            the user's id in tms-user
     * @param password
     *            plain-text password (will be BCrypt hashed by tms-user)
     * @return map with success, reason, message
     */
    public Map<String, Object> setUserPassword(long userId, String password) {
        logRequest("SetUserPassword", String.valueOf(userId));
        try {
            var resp = userStub.setUserPassword(com.tms.report.grpc.user.SetUserPasswordRequest.newBuilder()
                    .setUserId(userId).setPassword(password).build());
            Map<String, Object> result = new HashMap<>();
            result.put("success", resp.getSuccess());
            result.put("reason", resp.getReason());
            result.put("message", resp.getMessage());
            return result;
        } catch (StatusRuntimeException e) {
            throw grpcError("SetUserPassword", String.valueOf(userId), e);
        }
    }

    /**
     * Find a user by email address. Returns exists=true and user_id if found.
     *
     * @param email
     *            the email address to look up
     * @return map with exists (boolean) and user_id (Long, only if exists)
     */
    public Map<String, Object> findUserByEmail(String email) {
        logRequest("FindUserByEmail", email);
        try {
            var resp = userStub
                    .findUserByEmail(com.tms.report.grpc.user.FindUserRequest.newBuilder().setValue(email).build());
            Map<String, Object> result = new HashMap<>();
            result.put("exists", resp.getExists());
            if (resp.getExists()) {
                result.put("user_id", resp.getUserId());
            }
            return result;
        } catch (StatusRuntimeException e) {
            throw grpcError("FindUserByEmail", email, e);
        }
    }

    /**
     * Find a user by phone number. Returns exists=true and user_id if found.
     *
     * @param phoneNumber
     *            the phone number to look up
     * @return map with exists (boolean) and user_id (Long, only if exists)
     */
    public Map<String, Object> findUserByPhoneNumber(String phoneNumber) {
        log.info("FindUserByPhoneNumber: calling gRPC with phoneNumber={}", phoneNumber);
        try {
            var resp = userStub.findUserByPhoneNumber(
                    com.tms.report.grpc.user.FindUserRequest.newBuilder().setValue(phoneNumber).build());
            log.info("FindUserByPhoneNumber: response exists={} userId={}", resp.getExists(), resp.getUserId());
            Map<String, Object> result = new HashMap<>();
            result.put("exists", resp.getExists());
            if (resp.getExists()) {
                result.put("user_id", resp.getUserId());
            }
            return result;
        } catch (StatusRuntimeException e) {
            log.error("FindUserByPhoneNumber: gRPC error for phoneNumber={}: {}", phoneNumber, e.getMessage(), e);
            throw grpcError("FindUserByPhoneNumber", phoneNumber, e);
        }
    }

    /**
     * Get a user's profile from tms-user by user ID.
     *
     * @param userId
     *            the user's id in tms-user
     * @return map with user profile data: type, email, phone_number, first_name,
     *         last_name, business_name
     */
    public Map<String, Object> getUserProfile(long userId) {
        logRequest("GetUserProfile", String.valueOf(userId));
        try {
            var resp = userStub.getUserProfile(
                    com.tms.report.grpc.user.GetUserProfileRequest.newBuilder().setUserId(userId).build());
            Map<String, Object> result = new HashMap<>();
            result.put("user_id", resp.getUserId());
            result.put("type", resp.getType());
            result.put("email", resp.getEmail());
            result.put("phone_number", resp.getPhoneNumber());
            result.put("first_name", resp.getFirstName());
            result.put("last_name", resp.getLastName());
            result.put("business_name", resp.getBusinessName());
            return result;
        } catch (StatusRuntimeException e) {
            throw grpcError("GetUserProfile", String.valueOf(userId), e);
        }
    }

    // ── Terminal commands → config-service ──

    public Map<String, Object> createTerminal(String serial, String make, String model, String os) {
        String ref = Ulid.generate();
        logRequest("CreateTerminal", ref);
        try {
            var resp = configStub.createTerminal(CreateTerminalRequest.newBuilder().setSerial(serial).setMake(make)
                    .setModel(model).setOs(os).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateTerminal", ref, e);
        }
    }

    public Map<String, Object> createTerminalsBatch(List<Map<String, Object>> terminals) {
        String ref = Ulid.generate();
        logRequest("CreateTerminalsBatch", ref);
        try {
            var builder = CreateTerminalsBatchRequest.newBuilder();
            for (var t : terminals) {
                builder.addTerminals(TerminalEntry.newBuilder().setSerial(str(t, "serial")).setMake(str(t, "make"))
                        .setModel(str(t, "model")).setOs(str(t, "os")).build());
            }
            var resp = configStub.createTerminalsBatch(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateTerminalsBatch", ref, e);
        }
    }

    public Map<String, Object> updateTerminal(String terminalId, Map<String, Object> data) {
        String ref = ref(data);
        logRequest("UpdateTerminal", ref);
        try {
            var builder = UpdateTerminalRequest.newBuilder().setTerminalId(Long.parseLong(terminalId));
            if (data.containsKey("make"))
                builder.setMake(str(data, "make"));
            if (data.containsKey("model"))
                builder.setModel(str(data, "model"));
            if (data.containsKey("os"))
                builder.setOs(str(data, "os"));
            if (data.containsKey("is_active"))
                builder.setIsActive((Boolean) data.get("is_active"));

            var resp = configStub.updateTerminal(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateTerminal", ref, e);
        }
    }

    public Map<String, Object> mapTerminal(String terminalId, String userId) {
        String ref = Ulid.generate();
        logRequest("MapTerminal", ref);
        try {
            var resp = configStub.mapTerminal(MapTerminalRequest.newBuilder().setTerminalId(Long.parseLong(terminalId))
                    .setUserId(Long.parseLong(userId)).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("MapTerminal", ref, e);
        }
    }

    public Map<String, Object> unmapTerminal(String terminalId) {
        String ref = Ulid.generate();
        logRequest("UnmapTerminal", ref);
        try {
            var resp = configStub
                    .unmapTerminal(UnmapTerminalRequest.newBuilder().setTerminalId(Long.parseLong(terminalId)).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("UnmapTerminal", ref, e);
        }
    }

    // ── Configuration commands → config-service ──

    public Map<String, Object> updateConfiguration(String configurationId, String value) {
        String ref = Ulid.generate();
        logRequest("UpdateConfiguration", ref);
        try {
            var resp = configStub.updateConfiguration(UpdateConfigurationRequest.newBuilder()
                    .setId(Long.parseLong(configurationId)).setValue(value).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateConfiguration", ref, e);
        }
    }

    public Map<String, Object> createConfiguration(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("CreateConfiguration", ref);
        try {
            var builder = CreateConfigurationRequest.newBuilder();
            if (data.containsKey("module"))
                builder.setModule(str(data, "module"));
            if (data.containsKey("type"))
                builder.setType(str(data, "type"));
            if (data.containsKey("expression"))
                builder.setExpression(str(data, "expression"));
            if (data.containsKey("value"))
                builder.setValue(str(data, "value"));
            if (data.containsKey("description"))
                builder.setDescription(str(data, "description"));
            if (data.containsKey("name"))
                builder.setName(str(data, "name"));

            ConfigCommandResponse resp = configStub.createConfiguration(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateConfiguration", ref, e);
        }
    }

    public Map<String, Object> deleteConfiguration(String configurationId) {
        String ref = Ulid.generate();
        logRequest("DeleteConfiguration", ref);
        try {
            var resp = configStub.deleteConfiguration(
                    DeleteConfigurationRequest.newBuilder().setId(Long.parseLong(configurationId)).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("DeleteConfiguration", ref, e);
        }
    }

    // ── DataPlan commands → config-service ──

    public Map<String, Object> createDataPlan(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("CreateDataPlan", ref);
        try {
            var resp = configStub.createDataPlan(buildDataPlanRequest(data));
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateDataPlan", ref, e);
        }
    }

    public Map<String, Object> updateDataPlan(String dataPlanId, Map<String, Object> data) {
        String ref = ref(data);
        logRequest("UpdateDataPlan", ref);
        try {
            var builder = buildDataPlanRequest(data).toBuilder().setId(Long.parseLong(dataPlanId));
            var resp = configStub.updateDataPlan(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateDataPlan", ref, e);
        }
    }

    public Map<String, Object> deleteDataPlan(String dataPlanId) {
        String ref = Ulid.generate();
        logRequest("DeleteDataPlan", ref);
        try {
            var resp = configStub
                    .deleteDataPlan(DeleteDataPlanRequest.newBuilder().setId(Long.parseLong(dataPlanId)).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("DeleteDataPlan", ref, e);
        }
    }

    public Map<String, Object> importDataPlans() {
        String ref = Ulid.generate();
        logRequest("ImportDataPlans", ref);
        try {
            var resp = configStub.importDataPlans(ImportDataPlansRequest.newBuilder().build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ImportDataPlans", ref, e);
        }
    }

    // ── Provider balance commands → config-service ──

    /**
     * Trigger an immediate on-demand balance poll for a provider. Config-service
     * broadcasts a refresh command; the owning provider re-polls and republishes
     * its balance, which lands in provider_balances shortly after.
     */
    public Map<String, Object> refreshProviderBalance(String providerCode) {
        String ref = Ulid.generate();
        logRequest("RefreshProviderBalance", ref);
        try {
            var resp = configStub.refreshProviderBalance(RefreshProviderBalanceRequest.newBuilder()
                    .setProviderCode(providerCode).setTriggeredBy(currentAdminName()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("RefreshProviderBalance", ref, e);
        }
    }

    /**
     * Trigger an immediate on-demand card-key (TMK/TSK/TPK) re-download for a card
     * processor (nibss/upsl/interswitch). Config-service broadcasts a refresh
     * command; the owning processor self-filters, clears its stored keys and
     * re-downloads them from the switch. Fire-and-forget — the command is processed
     * asynchronously by the provider.
     */
    public Map<String, Object> refreshProviderCardKeys(String providerCode) {
        String ref = Ulid.generate();
        logRequest("RefreshProviderCardKeys", ref);
        try {
            var resp = configStub.refreshProviderCardKeys(RefreshProviderCardKeysRequest.newBuilder()
                    .setProviderCode(providerCode).setTriggeredBy(currentAdminName()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("RefreshProviderCardKeys", ref, e);
        }
    }

    // ── Balance Limit commands → config-service ──

    public Map<String, Object> setMaxBalance(String userType, String tier, String value, String description) {
        String ref = Ulid.generate();
        logRequest("SetMaxBalance", ref);
        try {
            var builder = SetMaxBalanceRequest.newBuilder().setUserType(userType).setTier(tier).setValue(value);
            if (description != null)
                builder.setDescription(description);
            var resp = configStub.setMaxBalance(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("SetMaxBalance", ref, e);
        }
    }

    public Map<String, Object> removeMaxBalance(String userType, String tier) {
        String ref = Ulid.generate();
        logRequest("RemoveMaxBalance", ref);
        try {
            var resp = configStub
                    .removeMaxBalance(RemoveMaxBalanceRequest.newBuilder().setUserType(userType).setTier(tier).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("RemoveMaxBalance", ref, e);
        }
    }

    // ── TID commands → config-service ──

    public Map<String, Object> createTid(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("CreateTid", ref);
        try {
            var builder = CreateTidRequest.newBuilder();
            if (data.containsKey("terminal_id"))
                builder.setTerminalId(str(data, "terminal_id"));
            if (data.containsKey("merchant_id"))
                builder.setMerchantId(str(data, "merchant_id"));

            var resp = configStub.createTid(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateTid", ref, e);
        }
    }

    public Map<String, Object> updateTid(String tidId, Map<String, Object> data) {
        String ref = ref(data);
        logRequest("UpdateTid", ref);
        try {
            var builder = UpdateTidRequest.newBuilder().setTidId(Long.parseLong(tidId));
            if (data.containsKey("terminal_id"))
                builder.setTerminalId(str(data, "terminal_id"));
            if (data.containsKey("merchant_id"))
                builder.setMerchantId(str(data, "merchant_id"));
            if (data.containsKey("internal"))
                builder.setInternal((Boolean) data.get("internal"));
            if (data.containsKey("processor"))
                builder.setProcessor(str(data, "processor")).setUpdateProcessor(true);

            var resp = configStub.updateTid(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateTid", ref, e);
        }
    }

    public Map<String, Object> deleteTid(String tidId) {
        String ref = tidId;
        logRequest("DeleteTid", ref);
        try {
            var resp = configStub.deleteTid(DeleteTidRequest.newBuilder().setTidId(Long.parseLong(tidId)).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("DeleteTid", ref, e);
        }
    }

    // ── Settlement commands → settlement-service ──

    public Map<String, Object> createSettlement(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("CreateSettlement", ref);
        try {
            var builder = CreateSettlementRequest.newBuilder().setReference(ref);
            if (data.containsKey("terminal_id"))
                builder.setTerminalId(str(data, "terminal_id"));
            if (data.containsKey("merchant_id"))
                builder.setMerchantId(str(data, "merchant_id"));
            if (data.containsKey("amount"))
                builder.setAmount(dbl(data, "amount"));
            if (data.containsKey("acquirer_fee"))
                builder.setAcquirerFee(dbl(data, "acquirer_fee"));
            if (data.containsKey("currency"))
                builder.setCurrency(str(data, "currency"));
            if (data.containsKey("status"))
                builder.setStatus(str(data, "status"));
            if (data.containsKey("card_scheme"))
                builder.setCardScheme(str(data, "card_scheme"));
            if (data.containsKey("region"))
                builder.setRegion(str(data, "region"));
            if (data.containsKey("bank"))
                builder.setBank(str(data, "bank"));

            var resp = settlementStub.createSettlement(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), null);
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateSettlement", ref, e);
        }
    }

    // ── Notification commands → notification-service ──

    public Map<String, Object> sendNotification(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("SendNotification", ref);
        try {
            var builder = SendNotificationCommandRequest.newBuilder().setReference(ref)
                    .setActor(buildNotificationActor());
            if (data.containsKey("notifiable_id"))
                builder.setNotifiableId(str(data, "notifiable_id"));
            if (data.containsKey("subject"))
                builder.setSubject(str(data, "subject"));
            if (data.containsKey("message"))
                builder.setMessage(str(data, "message"));
            if (data.containsKey("channels")) {
                Object ch = data.get("channels");
                if (ch instanceof java.util.List<?> list) {
                    list.forEach(c -> builder.addChannels(c.toString()));
                } else if (ch instanceof String s) {
                    // comma-separated or single value
                    for (String c : s.split(",")) {
                        String trimmed = c.trim();
                        if (!trimmed.isEmpty())
                            builder.addChannels(trimmed);
                    }
                }
            }
            if (data.containsKey("data")) {
                Object d = data.get("data");
                if (d instanceof java.util.Map<?, ?> map) {
                    map.forEach((k, v) -> {
                        if (k != null && v != null)
                            builder.putData(k.toString(), v.toString());
                    });
                }
            }

            NotificationCommandResponse resp = notificationStub.sendNotification(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("SendNotification", ref, e);
        }
    }

    public Map<String, Object> sendSms(String phoneNumber, String message) {
        String ref = Ulid.generate();
        logRequest("SendSms", ref);
        try {
            var resp = notificationStub
                    .sendSms(SendSmsRequest.newBuilder().setPhoneNumber(phoneNumber).setMessage(message).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), null);
        } catch (StatusRuntimeException e) {
            throw grpcError("SendSms", ref, e);
        }
    }

    /**
     * Send an email via the notification service using a named Qute template. The
     * template lives in the notification service under
     * {@code templates/emails/<templateName>.html}. Falls back to raw
     * {@code htmlBody} if the template is missing on that side.
     */
    public Map<String, Object> sendEmail(String to, String subject, String templateName,
            Map<String, String> templateData) {
        String ref = Ulid.generate();
        logRequest("SendEmail", ref);
        try {
            var builder = SendEmailRequest.newBuilder().setTo(to).setSubject(subject);
            if (templateName != null && !templateName.isBlank()) {
                builder.setTemplateName(templateName);
            }
            if (templateData != null) {
                templateData.forEach((k, v) -> {
                    if (k != null && v != null) {
                        builder.putTemplateData(k, v);
                    }
                });
            }
            var resp = notificationStub.sendEmail(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), null);
        } catch (StatusRuntimeException e) {
            throw grpcError("SendEmail", ref, e);
        }
    }

    // ── Manual Funding commands → wallet-service ──

    public Map<String, Object> createManualFunding(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("CreateManualFunding", ref);
        try {
            var builder = CreateManualFundingRequest.newBuilder().setReference(ref).setActor(buildWalletActor());
            if (data.containsKey("beneficiary"))
                builder.setBeneficiary(str(data, "beneficiary"));
            if (data.containsKey("user_id"))
                builder.setUserId(str(data, "user_id"));
            if (data.containsKey("amount"))
                builder.setAmount(dbl(data, "amount"));
            if (data.containsKey("wallet_type"))
                builder.setWalletType(str(data, "wallet_type"));
            if (data.containsKey("narration"))
                builder.setNarration(str(data, "narration"));
            if (data.containsKey("entry_type"))
                builder.setEntryType(str(data, "entry_type"));

            WalletCommandResponse resp = walletStub.createManualFunding(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateManualFunding", ref, e);
        }
    }

    public Map<String, Object> approveManualFunding(String manualFundingId) {
        String ref = Ulid.generate();
        logRequest("ApproveManualFunding", ref);
        try {
            var resp = walletStub.approveManualFunding(ApproveManualFundingRequest.newBuilder().setReference(ref)
                    .setManualFundingId(manualFundingId).setActor(buildWalletActor()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ApproveManualFunding", ref, e);
        }
    }

    public Map<String, Object> rejectManualFunding(String manualFundingId, String reason) {
        String ref = Ulid.generate();
        logRequest("RejectManualFunding", ref);
        try {
            var resp = walletStub.rejectManualFunding(RejectManualFundingRequest.newBuilder().setReference(ref)
                    .setManualFundingId(manualFundingId).setReason(reason).setActor(buildWalletActor()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("RejectManualFunding", ref, e);
        }
    }

    // ── Commission Transfer → wallet-service ──

    public Map<String, Object> createCommissionTransfer(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("CreateCommissionTransfer", ref);
        try {
            var builder = CreateCommissionTransferRequest.newBuilder().setReference(ref).setActor(buildWalletActor());
            if (data.containsKey("amount"))
                builder.setAmount(dbl(data, "amount"));
            if (data.containsKey("payment_method"))
                builder.setPaymentMethod(str(data, "payment_method"));

            WalletCommandResponse resp = walletStub.createCommissionTransfer(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateCommissionTransfer", ref, e);
        }
    }

    // ── PND (Post No Debit) → wallet-service ──

    public Map<String, Object> setPnd(long userId, boolean pnd, String reason) {
        return setPnd(userId, pnd, reason, null, null);
    }

    /**
     * Source-aware admin PND. The admin's user id is forwarded as
     * {@code applied_by_user_id} for the audit trail; the source defaults to
     * {@code admin} on the wallet side, with {@code source_reference =
     * "admin_<adminId>"} so two different admins applying restrictions on the same
     * user create two distinct holds.
     *
     * <p>
     * On <strong>release</strong> ({@code pnd=false}) the wallet side ignores
     * source / source_reference and lifts every active hold for the user except
     * {@code tamper}, via the {@code release_all} flag. The intent of the admin
     * Remove PND endpoint is "lift the restriction this user is complaining about"
     * — and after the May 2026 sourced-holds redesign, the matching hold can have
     * been raised by fraud, target_default, max_balance, or any other subsystem.
     * Trying to release only the {@code admin/admin_<adminId>} hold would silently
     * no-op when the active holds are from elsewhere, leaving the wallet PND'd
     * while showing the admin a success toast. Tamper holds remain sticky and
     * require their dedicated force-clear path.
     */
    public Map<String, Object> setPnd(long userId, boolean pnd, String reason, Long adminId, String releaseNote) {
        String ref = Ulid.generate();
        logRequest("SetPnd", ref);
        try {
            String sourceReference = adminId != null ? "admin_" + adminId : "admin_system";
            SetPndRequest.Builder builder = SetPndRequest.newBuilder().setUserId(userId).setPnd(pnd)
                    .setReason(reason != null ? reason : "").setSource("admin").setSourceReference(sourceReference);
            if (adminId != null) {
                builder.setAppliedByUserId(adminId);
            }
            if (releaseNote != null) {
                builder.setReleaseNote(releaseNote);
            }
            if (!pnd) {
                builder.setReleaseAll(true);
            }
            var resp = walletStub.setPnd(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), null);
        } catch (StatusRuntimeException e) {
            throw grpcError("SetPnd", ref, e);
        }
    }

    // ── Balance Reconciliation → wallet-service ──

    public Map<String, Object> reconcileBalance(long userId, String walletType) {
        return reconcileBalance(userId, walletType, false);
    }

    /**
     * Reconcile a wallet's balance to its statement history. When
     * {@code skipHoldRelease} is true the user's active PND holds are left
     * untouched (used by balance-discrepancy incident resolution, which must not
     * lift unrelated restrictions); when false the legacy admin-reset behavior
     * releases all non-tamper holds.
     */
    public Map<String, Object> reconcileBalance(long userId, String walletType, boolean skipHoldRelease) {
        String ref = Ulid.generate();
        logRequest("ReconcileBalance", ref);
        try {
            var resp = walletStub.reconcileBalance(com.tms.report.grpc.wallet.ReconcileBalanceRequest.newBuilder()
                    .setUserId(userId).setWalletType(walletType != null ? walletType : "default")
                    .setSkipHoldRelease(skipHoldRelease).setActor(buildWalletActor()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ReconcileBalance", ref, e);
        }
    }

    // ── Get User Balances → wallet-service ──

    /**
     * Get wallet balances for a user. Returns both main (default) and commission
     * wallet balances. Used by merchant dashboard to display wallet information.
     *
     * @param userId
     *            the user's id in the system
     * @return map with main_balance and commission_balance as strings
     */
    public Map<String, Object> getUserBalances(long userId) {
        logRequest("GetUserBalances", String.valueOf(userId));
        try {
            var resp = walletStub.getUserBalances(
                    com.tms.report.grpc.wallet.GetUserBalancesRequest.newBuilder().setUserId(userId).build());
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("main_balance", resp.getMainBalance());
            result.put("commission_balance", resp.getCommissionBalance());
            return result;
        } catch (StatusRuntimeException e) {
            log.warn("GetUserBalances failed for userId={}: {}", userId, e.getMessage());
            // Return zeros on error rather than throwing, so dashboard can still render
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("main_balance", "0");
            result.put("commission_balance", "0");
            return result;
        }
    }

    /**
     * List wallet statements for a user. Paginated statement history showing all
     * credits and debits. Used by merchant dashboard statements page.
     *
     * @param userId     the user's id in the system
     * @param walletType "default" or "commission", null for both
     * @param page       page number (1-indexed)
     * @param limit      items per page
     * @param startDate  ISO date yyyy-MM-dd, null for no start filter
     * @param endDate    ISO date yyyy-MM-dd, null for no end filter
     * @param type       "credit" or "debit", null for both
     * @return map with success, statements list, total, page, limit
     */
    public Map<String, Object> listStatements(long userId, String walletType, int page, int limit, String startDate,
            String endDate, String type) {
        logRequest("ListStatements", String.valueOf(userId));
        try {
            var builder = com.tms.report.grpc.wallet.ListStatementsRequest.newBuilder().setUserId(userId).setPage(page)
                    .setLimit(limit);
            if (walletType != null && !walletType.isEmpty()) {
                builder.setWalletType(walletType);
            }
            if (startDate != null && !startDate.isEmpty()) {
                builder.setStartDate(startDate);
            }
            if (endDate != null && !endDate.isEmpty()) {
                builder.setEndDate(endDate);
            }
            if (type != null && !type.isEmpty()) {
                builder.setType(type);
            }

            var resp = walletStub.listStatements(builder.build());
            Map<String, Object> result = new HashMap<>();
            result.put("success", resp.getSuccess());
            result.put("total", resp.getTotal());
            result.put("page", resp.getPage());
            result.put("limit", resp.getLimit());

            List<Map<String, Object>> statements = new ArrayList<>();
            for (var s : resp.getStatementsList()) {
                Map<String, Object> stmt = new HashMap<>();
                stmt.put("id", s.getId());
                stmt.put("type", s.getType());
                stmt.put("amount", s.getAmount());
                stmt.put("previous_balance", s.getPreviousBalance());
                stmt.put("current_balance", s.getCurrentBalance());
                stmt.put("description", s.getDescription());
                stmt.put("category", s.getCategory());
                stmt.put("source_type", s.getSourceType());
                stmt.put("source_reference", s.getSourceReference());
                stmt.put("wallet_type", s.getWalletType());
                stmt.put("created_at", s.getCreatedAt());
                statements.add(stmt);
            }
            result.put("statements", statements);
            return result;
        } catch (StatusRuntimeException e) {
            log.warn("ListStatements failed for userId={}: {}", userId, e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("statements", List.of());
            result.put("total", 0L);
            result.put("page", page);
            result.put("limit", limit);
            return result;
        }
    }

    // ── Address / KYC commands → kyc-service ──

    public Map<String, Object> updateAddress(String addressId, Map<String, Object> data) {
        String ref = ref(data);
        logRequest("UpdateAddress", ref);
        try {
            var builder = UpdateAddressRequest.newBuilder().setAddressId(Long.parseLong(addressId));
            if (data.containsKey("address"))
                builder.setAddress(str(data, "address"));
            if (data.containsKey("state_id"))
                builder.setStateId(str(data, "state_id"));
            if (data.containsKey("lga"))
                builder.setLga(str(data, "lga"));

            var resp = kycStub.updateAddress(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateAddress", ref, e);
        }
    }

    public Map<String, Object> approveAddress(String addressId) {
        String ref = Ulid.generate();
        logRequest("ApproveAddress", ref);
        try {
            var resp = kycStub
                    .approveAddress(ApproveAddressRequest.newBuilder().setAddressId(Long.parseLong(addressId)).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ApproveAddress", ref, e);
        }
    }

    public Map<String, Object> rejectAddress(String addressId, String reason) {
        String ref = Ulid.generate();
        logRequest("RejectAddress", ref);
        try {
            var resp = kycStub.rejectAddress(RejectAddressRequest.newBuilder().setAddressId(Long.parseLong(addressId))
                    .setReason(reason).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("RejectAddress", ref, e);
        }
    }

    // ── Document commands → kyc-service ──

    public Map<String, Object> approveDocument(String documentId) {
        String ref = Ulid.generate();
        logRequest("ApproveDocument", ref);
        try {
            var resp = kycStub.approveDocument(
                    ApproveDocumentRequest.newBuilder().setDocumentId(Long.parseLong(documentId)).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ApproveDocument", ref, e);
        }
    }

    public Map<String, Object> rejectDocument(String documentId, String reason) {
        String ref = Ulid.generate();
        logRequest("RejectDocument", ref);
        try {
            var resp = kycStub.rejectDocument(RejectDocumentRequest.newBuilder()
                    .setDocumentId(Long.parseLong(documentId)).setReason(reason).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("RejectDocument", ref, e);
        }
    }

    // ── Verification commands → kyc-service ──

    public Map<String, Object> approveVerification(String verificationId, String type) {
        String ref = Ulid.generate();
        logRequest("ApproveVerification", ref);
        try {
            var resp = kycStub.approveVerification(ApproveVerificationRequest.newBuilder()
                    .setVerificationId(Long.parseLong(verificationId)).setType(type).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ApproveVerification", ref, e);
        }
    }

    public Map<String, Object> rejectVerification(String verificationId, String type, String reason) {
        String ref = Ulid.generate();
        logRequest("RejectVerification", ref);
        try {
            var resp = kycStub.rejectVerification(RejectVerificationRequest.newBuilder()
                    .setVerificationId(Long.parseLong(verificationId)).setType(type).setReason(reason).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("RejectVerification", ref, e);
        }
    }

    // ── Business Application commands → kyc-service ──

    public Map<String, Object> approveBusinessApplication(String applicationId) {
        String ref = Ulid.generate();
        logRequest("ApproveBusinessApplication", ref);
        try {
            var resp = kycStub.approveBusinessApplication(ApproveBusinessApplicationRequest.newBuilder()
                    .setApplicationId(Long.parseLong(applicationId)).setReviewer(currentAdminName()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ApproveBusinessApplication", ref, e);
        }
    }

    public Map<String, Object> rejectBusinessApplication(String applicationId, String reason) {
        String ref = Ulid.generate();
        logRequest("RejectBusinessApplication", ref);
        try {
            var resp = kycStub.rejectBusinessApplication(
                    RejectBusinessApplicationRequest.newBuilder().setApplicationId(Long.parseLong(applicationId))
                            .setReason(reason != null ? reason : "").setReviewer(currentAdminName()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("RejectBusinessApplication", ref, e);
        }
    }

    public Map<String, Object> resyncBusinessName(String applicationId) {
        String ref = Ulid.generate();
        logRequest("ResyncBusinessName", ref);
        try {
            var resp = kycStub.resyncBusinessName(com.tms.report.grpc.kyc.ResyncBusinessNameRequest.newBuilder()
                    .setApplicationId(Long.parseLong(applicationId)).setReviewer(currentAdminName()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ResyncBusinessName", ref, e);
        }
    }

    /**
     * Compliance secondary review of an aggregator-approved/-rejected KYC record.
     * {@code type} is "address" or "business_application"; {@code cleared=true}
     * confirms the aggregator's decision, {@code false} overturns (reverses) it.
     */
    public Map<String, Object> secondaryReviewKyc(String type, String recordId, boolean cleared) {
        String ref = Ulid.generate();
        logRequest("SecondaryReview", ref);
        try {
            var resp = kycStub.secondaryReview(com.tms.report.grpc.kyc.SecondaryReviewRequest.newBuilder().setType(type)
                    .setRecordId(Long.parseLong(recordId)).setCleared(cleared).setReviewer(currentAdminName()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("SecondaryReview", ref, e);
        }
    }

    /**
     * Admin/compliance account suspension. Staff-imposed lock distinct from the
     * self-liftable freeze — {@code by_type="admin"} so the user-service marks it
     * as outranking any aggregator-placed suspension.
     */
    public Map<String, Object> suspendUser(long userId, String reason) {
        String ref = Ulid.generate();
        logRequest("SuspendUser", ref);
        try {
            var resp = userStub.suspendUser(com.tms.report.grpc.user.SuspendUserRequest.newBuilder().setUserId(userId)
                    .setReason(reason != null ? reason : "").setBy(currentAdminName()).setByType("admin").build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getReason());
        } catch (StatusRuntimeException e) {
            throw grpcError("SuspendUser", ref, e);
        }
    }

    public Map<String, Object> unsuspendUser(long userId) {
        String ref = Ulid.generate();
        logRequest("UnsuspendUser", ref);
        try {
            var resp = userStub.unsuspendUser(com.tms.report.grpc.user.UnsuspendUserRequest.newBuilder()
                    .setUserId(userId).setBy(currentAdminName()).setByType("admin").build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getReason());
        } catch (StatusRuntimeException e) {
            throw grpcError("UnsuspendUser", ref, e);
        }
    }

    /**
     * Admin/compliance account block. The hardest lock — the blocked user is
     * rejected outright at {@code /auth/login} (no token issued) and every existing
     * session is revoked. Distinct from suspension, which still issues a token so
     * the user can reach the in-app "contact support" CTA.
     */
    public Map<String, Object> blockUser(long userId, String reason) {
        String ref = Ulid.generate();
        logRequest("BlockUser", ref);
        try {
            var resp = userStub.blockUser(com.tms.report.grpc.user.BlockUserRequest.newBuilder().setUserId(userId)
                    .setReason(reason != null ? reason : "").setBy(currentAdminName()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getReason());
        } catch (StatusRuntimeException e) {
            throw grpcError("BlockUser", ref, e);
        }
    }

    public Map<String, Object> unblockUser(long userId) {
        String ref = Ulid.generate();
        logRequest("UnblockUser", ref);
        try {
            var resp = userStub.unblockUser(com.tms.report.grpc.user.UnblockUserRequest.newBuilder().setUserId(userId)
                    .setBy(currentAdminName()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getReason());
        } catch (StatusRuntimeException e) {
            throw grpcError("UnblockUser", ref, e);
        }
    }

    /**
     * Admin override of the self-service liveness unfreeze. Lifts {@code frozen_at}
     * and resets the PIN failed-attempt streak so the account isn't re-frozen on
     * the next wrong PIN.
     */
    public Map<String, Object> unfreezeUser(long userId) {
        String ref = Ulid.generate();
        logRequest("UnfreezeUser", ref);
        try {
            var resp = userStub.unfreezeUser(com.tms.report.grpc.user.UnfreezeUserRequest.newBuilder().setUserId(userId)
                    .setBy(currentAdminName()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getReason());
        } catch (StatusRuntimeException e) {
            throw grpcError("UnfreezeUser", ref, e);
        }
    }

    /**
     * Clears the user's bound device pointers and revokes sessions so they can sign
     * in on a new handset without the device-change liveness challenge.
     */
    public Map<String, Object> resetDevice(long userId) {
        String ref = Ulid.generate();
        logRequest("ResetDevice", ref);
        try {
            var resp = userStub.resetDevice(com.tms.report.grpc.user.ResetDeviceRequest.newBuilder().setUserId(userId)
                    .setBy(currentAdminName()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getReason());
        } catch (StatusRuntimeException e) {
            throw grpcError("ResetDevice", ref, e);
        }
    }

    /**
     * Clears only the device-change timestamps, lifting the temporary new-device
     * transaction cap while leaving the bound device and sessions intact.
     */
    public Map<String, Object> clearNewDeviceLimit(long userId) {
        String ref = Ulid.generate();
        logRequest("ClearNewDeviceLimit", ref);
        try {
            var resp = userStub.clearNewDeviceLimit(com.tms.report.grpc.user.ClearNewDeviceLimitRequest.newBuilder()
                    .setUserId(userId).setBy(currentAdminName()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getReason());
        } catch (StatusRuntimeException e) {
            throw grpcError("ClearNewDeviceLimit", ref, e);
        }
    }

    private String currentReviewer() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof MerchantUserDetails details) {
                return details.getUsername();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "admin";
    }

    // ── Funding Alert commands → config-service ──

    public Map<String, Object> createFundingAlert(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("CreateFundingAlert", ref);
        try {
            var builder = CreateFundingAlertRequest.newBuilder();
            if (data.containsKey("provider_id"))
                builder.setProviderId(Long.parseLong(str(data, "provider_id")));
            if (data.containsKey("amount"))
                builder.setAmount(dbl(data, "amount"));
            if (data.containsKey("interval"))
                builder.setInterval(str(data, "interval"));
            if (!str(data, "notify_via").isEmpty())
                builder.setNotifyVia(str(data, "notify_via"));
            addStringList(builder::addAllEmails, data.get("emails"));
            addStringList(builder::addAllPhoneNumbers, data.get("phone_numbers"));
            addStringList(builder::addAllAdminIds, data.get("admin_ids"));

            var resp = configStub.createFundingAlert(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateFundingAlert", ref, e);
        }
    }

    public Map<String, Object> updateFundingAlert(String fundingAlertId, Map<String, Object> data) {
        String ref = ref(data);
        logRequest("UpdateFundingAlert", ref);
        try {
            var builder = UpdateFundingAlertRequest.newBuilder().setFundingAlertId(Long.parseLong(fundingAlertId));
            if (data.containsKey("provider_id"))
                builder.setProviderId(Long.parseLong(str(data, "provider_id")));
            if (data.containsKey("amount"))
                builder.setAmount(dbl(data, "amount"));
            if (data.containsKey("interval"))
                builder.setInterval(str(data, "interval"));
            if (!str(data, "notify_via").isEmpty())
                builder.setNotifyVia(str(data, "notify_via"));
            addStringList(builder::addAllEmails, data.get("emails"));
            addStringList(builder::addAllPhoneNumbers, data.get("phone_numbers"));
            addStringList(builder::addAllAdminIds, data.get("admin_ids"));

            var resp = configStub.updateFundingAlert(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateFundingAlert", ref, e);
        }
    }

    public Map<String, Object> deleteFundingAlert(String fundingAlertId) {
        String ref = Ulid.generate();
        logRequest("DeleteFundingAlert", ref);
        try {
            var resp = configStub.deleteFundingAlert(
                    DeleteFundingAlertRequest.newBuilder().setFundingAlertId(Long.parseLong(fundingAlertId)).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("DeleteFundingAlert", ref, e);
        }
    }

    // ── Instant settlement commands → config-service ──

    public Map<String, Object> enrollInstantSettlement(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("EnrollInstantSettlement", ref);
        try {
            var builder = EnrollInstantSettlementRequest.newBuilder().setUserId(Long.parseLong(str(data, "user_id")));
            if (data.containsKey("notes"))
                builder.setNotes(str(data, "notes"));
            applyWindowFields(data, builder::setWindowEnabled, builder::setSettlementTimes,
                    builder::setDestinationAccountNumber, builder::setDestinationBankCode,
                    builder::setDestinationAccountName);
            builder.setApprovedBy(currentAdminName());

            var resp = configStub.enrollInstantSettlement(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("EnrollInstantSettlement", ref, e);
        }
    }

    public Map<String, Object> updateSettlementWindow(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("UpdateSettlementWindow", ref);
        try {
            var builder = UpdateSettlementWindowRequest.newBuilder().setUserId(Long.parseLong(str(data, "user_id")));
            applyWindowFields(data, builder::setWindowEnabled, builder::setSettlementTimes,
                    builder::setDestinationAccountNumber, builder::setDestinationBankCode,
                    builder::setDestinationAccountName);
            var resp = configStub.updateSettlementWindow(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateSettlementWindow", ref, e);
        }
    }

    public Map<String, Object> checkInstantSettlement(long userId) {
        String ref = Ulid.generate();
        logRequest("CheckInstantSettlement", ref);
        try {
            var resp = configStub
                    .checkInstantSettlement(CheckInstantSettlementRequest.newBuilder().setUserId(userId).build());
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("active", resp.getActive());
            m.put("status", resp.getStatus());
            m.put("window_enabled", resp.getWindowEnabled());
            m.put("settlement_times", resp.getSettlementTimes());
            m.put("destination_account_number", resp.getDestinationAccountNumber());
            m.put("destination_bank_code", resp.getDestinationBankCode());
            m.put("destination_account_name", resp.getDestinationAccountName());
            return m;
        } catch (StatusRuntimeException e) {
            throw grpcError("CheckInstantSettlement", ref, e);
        }
    }

    /**
     * Apply the optional settlement-window fields from a request map onto a
     * builder.
     */
    private void applyWindowFields(Map<String, Object> data, java.util.function.Consumer<Boolean> setEnabled,
            java.util.function.Consumer<String> setTimes, java.util.function.Consumer<String> setAccount,
            java.util.function.Consumer<String> setBank, java.util.function.Consumer<String> setName) {
        if (data.containsKey("window_enabled")) {
            setEnabled.accept(Boolean.parseBoolean(String.valueOf(data.get("window_enabled"))));
        }
        if (data.containsKey("settlement_times"))
            setTimes.accept(str(data, "settlement_times"));
        if (data.containsKey("destination_account_number"))
            setAccount.accept(str(data, "destination_account_number"));
        if (data.containsKey("destination_bank_code"))
            setBank.accept(str(data, "destination_bank_code"));
        if (data.containsKey("destination_account_name"))
            setName.accept(str(data, "destination_account_name"));
    }

    public Map<String, Object> suspendInstantSettlement(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("SuspendInstantSettlement", ref);
        try {
            var builder = SuspendInstantSettlementRequest.newBuilder().setUserId(Long.parseLong(str(data, "user_id")));
            if (data.containsKey("reason"))
                builder.setReason(str(data, "reason"));

            var resp = configStub.suspendInstantSettlement(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("SuspendInstantSettlement", ref, e);
        }
    }

    public Map<String, Object> revokeInstantSettlement(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("RevokeInstantSettlement", ref);
        try {
            var builder = RevokeInstantSettlementRequest.newBuilder().setUserId(Long.parseLong(str(data, "user_id")));
            if (data.containsKey("reason"))
                builder.setReason(str(data, "reason"));

            var resp = configStub.revokeInstantSettlement(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("RevokeInstantSettlement", ref, e);
        }
    }

    public Map<String, Object> reactivateInstantSettlement(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("ReactivateInstantSettlement", ref);
        try {
            var builder = ReactivateInstantSettlementRequest.newBuilder()
                    .setUserId(Long.parseLong(str(data, "user_id")));
            if (data.containsKey("notes"))
                builder.setNotes(str(data, "notes"));

            var resp = configStub.reactivateInstantSettlement(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ReactivateInstantSettlement", ref, e);
        }
    }

    // ── Transaction commands → transaction-service ──

    public Map<String, Object> requeryProvider(String reference) {
        String ref = Ulid.generate();
        logRequest("RequeryProvider", ref);
        try {
            var resp = transactionStub.requeryProvider(RequeryProviderRequest.newBuilder().setReference(ref)
                    .setTransactionReference(reference).setActor(buildTransactionActor()).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("RequeryProvider", ref, e);
        }
    }

    public Map<String, Object> createAdminTransfer(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("CreateAdminTransfer", ref);
        try {
            var builder = CreateAdminTransferRequest.newBuilder().setReference(ref).setActor(buildTransactionActor());
            if (data.containsKey("amount"))
                builder.setAmount(dbl(data, "amount"));
            if (data.containsKey("account_number"))
                builder.setAccountNumber(str(data, "account_number"));
            if (data.containsKey("bank_code"))
                builder.setBankCode(str(data, "bank_code"));
            if (data.containsKey("account_name"))
                builder.setAccountName(str(data, "account_name"));
            if (data.containsKey("narration"))
                builder.setNarration(str(data, "narration"));
            if (data.containsKey("expense_category"))
                builder.setExpenseCategory(str(data, "expense_category"));
            if (data.containsKey("original_reference"))
                builder.setOriginalReference(str(data, "original_reference"));

            var resp = transactionStub.createAdminTransfer(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateAdminTransfer", ref, e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> nameEnquiry(String accountNumber, String bankCode) {
        logRequest("NameEnquiry", accountNumber);
        try {
            var resp = transactionStub.nameEnquiry(
                    NameEnquiryGrpcRequest.newBuilder().setAccountNumber(accountNumber).setBankCode(bankCode).build());
            if (!resp.getSuccess()) {
                throw new GrpcException(resp.getMessage().isEmpty() ? "Name enquiry failed" : resp.getMessage());
            }
            // Parse data_json to return the account details directly
            if (!resp.getDataJson().isEmpty()) {
                var mapper = new tools.jackson.databind.ObjectMapper();
                return mapper.readValue(resp.getDataJson(), Map.class);
            }
            return toMap(resp.getSuccess(), accountNumber, resp.getMessage(), resp.getDataJson());
        } catch (GrpcException e) {
            throw e;
        } catch (StatusRuntimeException e) {
            throw grpcError("NameEnquiry", accountNumber, e);
        } catch (Exception e) {
            throw new GrpcException("Name enquiry failed: " + e.getMessage());
        }
    }

    /**
     * Name enquiry via HTTP — calls the transaction service's existing REST
     * endpoint directly. This is the same endpoint the mobile app uses through the
     * gateway.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> nameEnquiryHttp(String accountNumber, String bankCode) {
        try {
            var ep = props.getService("transaction");
            String url = "http://" + ep.getHost() + ":" + ep.getPort() + "/bank-transfers/name-enquiry";

            String body = String.format("{\"account_number\":\"%s\",\"bank_code\":\"%s\"}", accountNumber, bankCode);

            var httpClient = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();
            var request = java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();

            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            var mapper = new tools.jackson.databind.ObjectMapper();
            Map<String, Object> result = mapper.readValue(response.body(), Map.class);

            if (response.statusCode() >= 400) {
                String message = result.getOrDefault("message", "Name enquiry failed").toString();
                throw new GrpcException(message);
            }

            Object data = result.get("data");
            if (data instanceof Map) {
                return (Map<String, Object>) data;
            }
            return result;
        } catch (GrpcException e) {
            throw e;
        } catch (Exception e) {
            log.error("Name enquiry HTTP failed: {}", e.getMessage());
            throw new GrpcException("Name enquiry failed: " + e.getMessage());
        }
    }

    public Map<String, Object> markCompleted(String reference) {
        return mark(reference, true);
    }

    public Map<String, Object> markFailed(String reference) {
        return mark(reference, false);
    }

    private Map<String, Object> mark(String reference, boolean complete) {
        String ref = Ulid.generate();
        String method = complete ? "MarkCompleted" : "MarkFailed";
        logRequest(method, ref);
        try {
            var builder = MarkTransactionRequest.newBuilder().setReference(ref).setActor(buildTransactionActor());
            if (reference != null)
                builder.setTransactionReference(reference);

            var resp = complete
                    ? transactionStub.markCompleted(builder.build())
                    : transactionStub.markFailed(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError(method, ref, e);
        }
    }

    public Map<String, Object> replayVirtualFunding(String reference) {
        String ref = Ulid.generate();
        logRequest("ReplayVirtualFunding", ref);
        try {
            var builder = ReplayVirtualFundingRequest.newBuilder().setReference(ref).setActor(buildTransactionActor());
            if (reference != null)
                builder.setTransactionReference(reference);

            var resp = transactionStub.replayVirtualFunding(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ReplayVirtualFunding", ref, e);
        }
    }

    // ── Ledger commands → ledger-service ──

    @SuppressWarnings("unchecked")
    public Map<String, Object> fixGlDiscrepancy(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("FixGlDiscrepancy", ref);
        try {
            var builder = FixDiscrepancyRequest.newBuilder();
            if (data.containsKey("duplicate_ids")) {
                List<Number> ids = (List<Number>) data.get("duplicate_ids");
                ids.forEach(id -> builder.addEntryIds(id.longValue()));
            }
            LedgerCommandResponse resp = ledgerStub.fixGlDiscrepancy(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), null);
        } catch (StatusRuntimeException e) {
            throw grpcError("FixGlDiscrepancy", ref, e);
        }
    }

    public Map<String, Object> detectGlDiscrepancies() {
        String ref = Ulid.generate();
        logRequest("DetectGlDiscrepancies", ref);
        try {
            DiscrepanciesResponse resp = ledgerStub
                    .detectGlDiscrepancies(DetectDiscrepanciesRequest.newBuilder().build());
            return toMap(true, ref, "Discrepancies detected", resp.toString());
        } catch (StatusRuntimeException e) {
            throw grpcError("DetectGlDiscrepancies", ref, e);
        }
    }

    /**
     * Record a single settlement landing — DR assets/bank, CR
     * assets/&lt;receivable_sub_category&gt;. Idempotent on the {@code
     * landing_reference} so re-running with the same key is a no-op.
     */
    public Map<String, Object> recordSettlementLanding(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("RecordSettlementLanding", ref);
        try {
            var builder = RecordSettlementLandingRequest.newBuilder()
                    .setReceivableSubCategory(stringOrEmpty(data.get("receivable_sub_category")))
                    .setAmount(stringOrEmpty(data.get("amount")))
                    .setLandingReference(stringOrEmpty(data.get("landing_reference")))
                    .setDescription(stringOrEmpty(data.get("description")))
                    .setActorEmail(stringOrEmpty(data.get("actor_email")));
            if (data.get("actor_user_id") instanceof Number n) {
                builder.setActorUserId(n.longValue());
            }
            LedgerCommandResponse resp = ledgerStub.recordSettlementLanding(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), null);
        } catch (StatusRuntimeException e) {
            throw grpcError("RecordSettlementLanding", ref, e);
        }
    }

    /**
     * Record a batch of landings against the same receivable, one entry per
     * transaction reference.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> recordSettlementLandings(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("RecordSettlementLandings", ref);
        try {
            var builder = RecordSettlementLandingsRequest.newBuilder()
                    .setReceivableSubCategory(stringOrEmpty(data.get("receivable_sub_category")))
                    .setActorEmail(stringOrEmpty(data.get("actor_email")));
            if (data.get("actor_user_id") instanceof Number n) {
                builder.setActorUserId(n.longValue());
            }
            List<Map<String, Object>> entries = (List<Map<String, Object>>) data.get("entries");
            if (entries != null) {
                for (Map<String, Object> raw : entries) {
                    builder.addEntries(LandingEntryItem.newBuilder()
                            .setTransactionReference(stringOrEmpty(raw.get("transaction_reference")))
                            .setAmount(stringOrEmpty(raw.get("amount"))).build());
                }
            }
            LedgerCommandResponse resp = ledgerStub.recordSettlementLandings(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), null);
        } catch (StatusRuntimeException e) {
            throw grpcError("RecordSettlementLandings", ref, e);
        }
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * Trigger a fiscal-period close on the ledger service. Idempotent on (year,
     * month) — repeated calls return the existing close record.
     */
    public Map<String, Object> closePeriod(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("ClosePeriod", ref);
        try {
            int year = data.get("year") instanceof Number n
                    ? n.intValue()
                    : Integer.parseInt(stringOrEmpty(data.get("year")));
            int month = data.get("month") instanceof Number n
                    ? n.intValue()
                    : Integer.parseInt(stringOrEmpty(data.get("month")));
            var builder = ClosePeriodRequest.newBuilder().setYear(year).setMonth(month)
                    .setActorEmail(stringOrEmpty(data.get("actor_email")));
            if (data.get("actor_user_id") instanceof Number n) {
                builder.setActorUserId(n.longValue());
            }
            PeriodCloseResponse resp = ledgerStub.closePeriod(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), recordToJson(resp.getRecord()));
        } catch (StatusRuntimeException e) {
            throw grpcError("ClosePeriod", ref, e);
        }
    }

    public Map<String, Object> listPeriodCloses(int limit) {
        String ref = Ulid.generate();
        logRequest("ListPeriodCloses", ref);
        try {
            PeriodClosesResponse resp = ledgerStub
                    .listPeriodCloses(ListPeriodClosesRequest.newBuilder().setLimit(limit).build());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (PeriodCloseRecord r : resp.getRecordsList()) {
                rows.add(recordToMap(r));
            }
            return Map.of("success", true, "reference", ref, "message", "ok", "data", rows);
        } catch (StatusRuntimeException e) {
            throw grpcError("ListPeriodCloses", ref, e);
        }
    }

    private static Map<String, Object> recordToMap(PeriodCloseRecord r) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("year", r.getYear());
        m.put("month", r.getMonth());
        m.put("period_label", r.getPeriodLabel());
        m.put("closed_at", r.getClosedAt());
        m.put("closed_by_email", r.getClosedByEmail());
        m.put("closing_entry_count", r.getClosingEntryCount());
        m.put("total_revenue_closed", r.getTotalRevenueClosed());
        m.put("total_expense_closed", r.getTotalExpenseClosed());
        m.put("net_to_retained_earnings", r.getNetToRetainedEarnings());
        return m;
    }

    private static String recordToJson(PeriodCloseRecord r) {
        if (r == null || r.getPeriodLabel().isEmpty()) {
            return null;
        }
        return String.format(
                "{\"year\":%d,\"month\":%d,\"period_label\":\"%s\",\"closed_at\":\"%s\","
                        + "\"closing_entry_count\":%d,\"total_revenue_closed\":\"%s\","
                        + "\"total_expense_closed\":\"%s\",\"net_to_retained_earnings\":\"%s\"}",
                r.getYear(), r.getMonth(), r.getPeriodLabel(), r.getClosedAt(), r.getClosingEntryCount(),
                r.getTotalRevenueClosed(), r.getTotalExpenseClosed(), r.getNetToRetainedEarnings());
    }

    // ── Provider Balance commands → config-service (cached) ──

    public Map<String, Object> getProviderSourceBalance(String providerCode) {
        String ref = Ulid.generate();
        logRequest("GetProviderSourceBalance", ref);
        try {
            var resp = configStub
                    .getProviderBalance(GetProviderBalanceRequest.newBuilder().setProviderCode(providerCode).build());
            String dataJson = String.format(
                    "{\"provider_code\":\"%s\",\"balance\":%s,\"source_account_number\":\"%s\",\"is_healthy\":%b}",
                    resp.getProviderCode(), resp.getBalance(), resp.getSourceAccountNumber(), resp.getIsHealthy());
            return toMap(true, ref, "Balance fetched", dataJson);
        } catch (StatusRuntimeException e) {
            throw grpcError("GetProviderSourceBalance", ref, e);
        }
    }

    public Map<String, Object> getAllProviderSourceBalances() {
        String ref = Ulid.generate();
        logRequest("GetAllProviderSourceBalances", ref);
        try {
            var resp = configStub.getAllProviderBalances(GetAllProviderBalancesRequest.newBuilder().build());
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < resp.getBalancesCount(); i++) {
                if (i > 0)
                    sb.append(",");
                var b = resp.getBalances(i);
                sb.append(String.format(
                        "{\"provider_code\":\"%s\",\"balance\":%s,\"source_account_number\":\"%s\",\"is_healthy\":%b}",
                        b.getProviderCode(), b.getBalance(), b.getSourceAccountNumber(), b.getIsHealthy()));
            }
            sb.append("]");
            return toMap(true, ref, "Provider balances fetched", sb.toString());
        } catch (StatusRuntimeException e) {
            throw grpcError("GetAllProviderSourceBalances", ref, e);
        }
    }

    // ── Bulk Payment commands → transaction-service ──

    public Map<String, Object> createBulkPayment(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("CreateBulkPayment", ref);
        try {
            var builder = CreateBulkPaymentRequest.newBuilder();
            if (data.containsKey("file_name"))
                builder.setFileName(str(data, "file_name"));
            if (data.containsKey("narration"))
                builder.setNarration(str(data, "narration"));
            if (data.containsKey("dispatch_type"))
                builder.setDispatchType(str(data, "dispatch_type"));
            if (data.containsKey("payment_type"))
                builder.setPaymentType(str(data, "payment_type"));
            if (data.containsKey("idempotency_key"))
                builder.setIdempotencyKey(str(data, "idempotency_key"));
            if (data.containsKey("scheduled_at"))
                builder.setScheduledAt(str(data, "scheduled_at"));

            // Add parsed CSV rows
            if (data.containsKey("rows")) {
                @SuppressWarnings("unchecked")
                var rows = (java.util.List<java.util.Map<String, String>>) data.get("rows");
                for (var row : rows) {
                    var rowBuilder = BulkPaymentRow.newBuilder();
                    if (row.containsKey("account_number"))
                        rowBuilder.setAccountNumber(row.get("account_number"));
                    if (row.containsKey("bank_code"))
                        rowBuilder.setBankCode(row.get("bank_code"));
                    if (row.containsKey("amount")) {
                        try {
                            rowBuilder.setAmount(Double.parseDouble(row.get("amount")));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (row.containsKey("narration"))
                        rowBuilder.setNarration(row.get("narration"));
                    if (row.containsKey("email"))
                        rowBuilder.setEmail(row.get("email"));
                    builder.addRows(rowBuilder.build());
                }
            }

            var resp = transactionStub.createBulkPayment(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateBulkPayment", ref, e);
        }
    }

    public Map<String, Object> disburseBulkPayment(long bulkPaymentId) {
        String ref = Ulid.generate();
        logRequest("DisburseBulkPayment", ref);
        try {
            var resp = transactionStub.disburseBulkPayment(
                    DisburseBulkPaymentRequest.newBuilder().setBulkPaymentId(bulkPaymentId).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("DisburseBulkPayment", ref, e);
        }
    }

    public Map<String, Object> reprocessBulkPaymentItem(long itemId) {
        String ref = Ulid.generate();
        logRequest("ReprocessBulkPaymentItem", ref);
        try {
            var resp = transactionStub
                    .reprocessBulkPaymentItem(ReprocessBulkPaymentItemRequest.newBuilder().setItemId(itemId).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ReprocessBulkPaymentItem", ref, e);
        }
    }

    // ── Health check (still HTTP for backward compat) ──

    public boolean health() {
        // Health check remains a simple connectivity test
        // Could be replaced with gRPC health checking protocol later
        try {
            // Quick test: call a lightweight RPC on any service
            configStub.getConfigValue(GetConfigValueRequest.newBuilder().setModule("health").setType("check").build());
            return true;
        } catch (Exception e) {
            log.warn("gRPC health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Reconciliation commands → transaction-service ──

    @SuppressWarnings("unchecked")
    public Map<String, Object> createReconciliationUpload(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("CreateReconciliationUpload", ref);
        try {
            var builder = CreateReconciliationUploadRequest.newBuilder();
            if (data.containsKey("file_name"))
                builder.setFileName(str(data, "file_name"));
            if (data.containsKey("file_path"))
                builder.setFilePath(str(data, "file_path"));
            if (data.containsKey("product_id"))
                builder.setProductId(str(data, "product_id"));
            if (data.containsKey("provider_id"))
                builder.setProviderId(str(data, "provider_id"));
            if (data.containsKey("match_field"))
                builder.setMatchField(str(data, "match_field"));
            if (data.containsKey("column_mapping"))
                builder.setColumnMappingJson(str(data, "column_mapping"));
            if (data.containsKey("status_mapping"))
                builder.setStatusMappingJson(str(data, "status_mapping"));
            if (data.containsKey("rows")) {
                List<String> rows = (List<String>) data.get("rows");
                builder.addAllRowsJson(rows);
            }

            var resp = transactionStub.createReconciliationUpload(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateReconciliationUpload", ref, e);
        }
    }

    public Map<String, Object> reconcileUpload(String uploadId) {
        String ref = Ulid.generate();
        logRequest("ReconcileUpload", ref);
        try {
            var resp = transactionStub
                    .reconcileUpload(ReconcileUploadRequest.newBuilder().setUploadId(Long.parseLong(uploadId)).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ReconcileUpload", ref, e);
        }
    }

    public Map<String, Object> reconcileItem(String uploadId, String itemId) {
        String ref = Ulid.generate();
        logRequest("ReconcileItem", ref);
        try {
            var resp = transactionStub.reconcileItem(ReconcileItemRequest.newBuilder()
                    .setUploadId(Long.parseLong(uploadId)).setItemId(Long.parseLong(itemId)).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("ReconcileItem", ref, e);
        }
    }

    public Map<String, Object> createReconciliationConfig(Map<String, Object> data) {
        String ref = ref(data);
        logRequest("CreateReconciliationConfig", ref);
        try {
            var builder = CreateReconciliationConfigRequest.newBuilder();
            if (data.containsKey("name"))
                builder.setName(str(data, "name"));
            if (data.containsKey("provider_id"))
                builder.setProviderId(str(data, "provider_id"));
            if (data.containsKey("product_id"))
                builder.setProductId(str(data, "product_id"));
            if (data.containsKey("match_field"))
                builder.setMatchField(str(data, "match_field"));
            if (data.containsKey("column_mapping"))
                builder.setColumnMappingJson(str(data, "column_mapping"));
            if (data.containsKey("status_mapping"))
                builder.setStatusMappingJson(str(data, "status_mapping"));
            if (data.containsKey("file_headers"))
                builder.setFileHeadersJson(str(data, "file_headers"));

            var resp = transactionStub.createReconciliationConfig(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateReconciliationConfig", ref, e);
        }
    }

    // ── Dispute commands → dispute-service ──

    public Map<String, Object> addDisputeConversation(long disputeId, String message) {
        String ref = Ulid.generate();
        logRequest("AddDisputeConversation", ref);
        try {
            var actor = buildDisputeActor();
            var resp = disputeStub.addConversation(AddConversationRequest.newBuilder().setDisputeId(disputeId)
                    .setMessage(message).setActor(actor).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("AddDisputeConversation", ref, e);
        }
    }

    public Map<String, Object> closeDispute(long disputeId) {
        String ref = Ulid.generate();
        logRequest("CloseDispute", ref);
        try {
            var actor = buildDisputeActor();
            var resp = disputeStub
                    .closeDispute(CloseDisputeRequest.newBuilder().setDisputeId(disputeId).setActor(actor).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CloseDispute", ref, e);
        }
    }

    public Map<String, Object> updateDispute(long disputeId, Map<String, Object> data) {
        String ref = Ulid.generate();
        logRequest("UpdateDispute", ref);
        try {
            var builder = UpdateDisputeRequest.newBuilder().setDisputeId(disputeId).setActor(buildDisputeActor());
            if (data.containsKey("description"))
                builder.setDescription(str(data, "description"));
            if (data.containsKey("status_code"))
                builder.setStatusCode(str(data, "status_code"));

            var resp = disputeStub.updateDispute(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateDispute", ref, e);
        }
    }

    public Map<String, Object> createDispute(long userId, String transactionReference, String subject, String message) {
        String ref = Ulid.generate();
        logRequest("CreateDispute", ref);
        try {
            var actor = buildDisputeActor();
            var builder = CreateDisputeRequest.newBuilder().setUserId(userId).setSubject(subject).setActor(actor);
            if (transactionReference != null && !transactionReference.isBlank())
                builder.setTransactionReference(transactionReference);
            if (message != null && !message.isBlank())
                builder.setMessage(message);

            var resp = disputeStub.createDispute(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("CreateDispute", ref, e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> updateReconciliationConfig(String configId, Map<String, Object> data) {
        String ref = ref(data);
        logRequest("UpdateReconciliationConfig", ref);
        try {
            var builder = UpdateReconciliationConfigRequest.newBuilder().setConfigId(Long.parseLong(configId));
            if (data.containsKey("name"))
                builder.setName(str(data, "name"));
            if (data.containsKey("provider_id"))
                builder.setProviderId(str(data, "provider_id"));
            if (data.containsKey("product_id"))
                builder.setProductId(str(data, "product_id"));
            if (data.containsKey("match_field"))
                builder.setMatchField(str(data, "match_field"));
            if (data.containsKey("column_mapping"))
                builder.setColumnMappingJson(str(data, "column_mapping"));
            if (data.containsKey("status_mapping"))
                builder.setStatusMappingJson(str(data, "status_mapping"));
            if (data.containsKey("file_headers"))
                builder.setFileHeadersJson(str(data, "file_headers"));

            var resp = transactionStub.updateReconciliationConfig(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateReconciliationConfig", ref, e);
        }
    }

    public Map<String, Object> deleteReconciliationConfig(String configId) {
        String ref = Ulid.generate();
        logRequest("DeleteReconciliationConfig", ref);
        try {
            var resp = transactionStub.deleteReconciliationConfig(
                    DeleteReconciliationConfigRequest.newBuilder().setConfigId(Long.parseLong(configId)).build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("DeleteReconciliationConfig", ref, e);
        }
    }

    // ═══════════════════════════════════════════
    // Generic command dispatcher (backward compat with ConfigurationController)
    // ═══════════════════════════════════════════

    /**
     * Dispatch a command by method name to the appropriate gRPC stub call. This
     * preserves backward compatibility with controllers that used the old
     * HTTP-based command pattern: POST /grpc/command/{method}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> command(String method, Map<String, Object> data) {
        return switch (method) {
            case "CreateConfiguration" -> createConfiguration(data);
            case "UpdateConfiguration" -> {
                String id = str(data, "configuration_id");
                yield updateConfigurationFull(id, data);
            }
            case "DeleteConfiguration" -> {
                String id = str(data, "configuration_id");
                yield deleteConfiguration(id);
            }
            default -> throw new GrpcException("Unknown command: " + method, "UNIMPLEMENTED", Map.of("method", method));
        };
    }

    /**
     * Full update that passes all fields (module, type, expression, value,
     * description). Used by the command() dispatcher for ConfigurationController
     * compatibility.
     */
    private Map<String, Object> updateConfigurationFull(String configurationId, Map<String, Object> data) {
        String ref = Ulid.generate();
        logRequest("UpdateConfiguration", ref);
        try {
            var builder = UpdateConfigurationRequest.newBuilder().setId(Long.parseLong(configurationId));
            if (data.containsKey("module"))
                builder.setModule(str(data, "module"));
            if (data.containsKey("type"))
                builder.setType(str(data, "type"));
            if (data.containsKey("expression"))
                builder.setExpression(str(data, "expression"));
            if (data.containsKey("value"))
                builder.setValue(str(data, "value"));
            if (data.containsKey("description"))
                builder.setDescription(str(data, "description"));
            if (data.containsKey("name"))
                builder.setName(str(data, "name"));

            var resp = configStub.updateConfiguration(builder.build());
            return toMap(resp.getSuccess(), ref, resp.getMessage(), resp.getDataJson());
        } catch (StatusRuntimeException e) {
            throw grpcError("UpdateConfiguration", ref, e);
        }
    }

    // ═══════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════

    private String ref(Map<String, Object> data) {
        Object r = data != null ? data.get("reference") : null;
        return r != null ? r.toString() : Ulid.generate();
    }

    private String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v == null)
            return "";
        if (v instanceof Map || v instanceof java.util.List) {
            try {
                return new ObjectMapper().writeValueAsString(v);
            } catch (Exception e) {
                return v.toString();
            }
        }
        return v.toString();
    }

    private double dbl(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v instanceof Number n)
            return n.doubleValue();
        if (v instanceof String s)
            return Double.parseDouble(s);
        return 0.0;
    }

    private int intVal(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v instanceof Number n)
            return n.intValue();
        if (v instanceof String s)
            return Integer.parseInt(s);
        return 0;
    }

    @SuppressWarnings("unchecked")
    private void addStringList(Consumer<Iterable<String>> adder, Object value) {
        if (value == null)
            return;
        if (value instanceof java.util.List<?> list) {
            adder.accept(list.stream().map(Object::toString).toList());
        }
    }

    private void logRequest(String method, String ref) {
        if (props.isLogging()) {
            log.info("gRPC → {} [ref={}]", method, ref);
        }
    }

    private Map<String, Object> toMap(boolean success, String reference, String message, String dataJson) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("reference", reference);
        result.put("message", message);
        result.put("data", dataJson);
        return result;
    }

    private GrpcException grpcError(String method, String ref, StatusRuntimeException e) {
        log.error("gRPC ✗ {} [ref={}]: {} - {}", method, ref, e.getStatus().getCode(), e.getMessage());
        String desc = e.getStatus().getDescription();
        if (desc == null || desc.isBlank()) {
            desc = e.getStatus().getCode().name();
            if (e.getCause() != null && e.getCause().getMessage() != null) {
                desc += ": " + e.getCause().getMessage();
            }
        }
        return new GrpcException("gRPC call failed: " + method + " - " + desc, e.getStatus().getCode().name(),
                Map.of("method", method, "reference", ref));
    }

    private com.tms.report.grpc.user.Actor buildUserActor() {
        MerchantUser admin = currentAdmin();
        var builder = com.tms.report.grpc.user.Actor.newBuilder();
        if (admin != null) {
            builder.setId(String.valueOf(admin.getId()));
            builder.setName(admin.getName());
            builder.setEmail(admin.getEmail());
        } else {
            builder.setId("system").setName("System").setEmail("system@tms.local");
        }
        return builder.build();
    }

    private com.tms.report.grpc.wallet.Actor buildWalletActor() {
        MerchantUser admin = currentAdmin();
        var builder = com.tms.report.grpc.wallet.Actor.newBuilder();
        if (admin != null) {
            builder.setId(String.valueOf(admin.getId()));
            builder.setName(admin.getName());
            builder.setEmail(admin.getEmail());
        } else {
            builder.setId("system").setName("System").setEmail("system@tms.local");
        }
        return builder.build();
    }

    private com.tms.report.grpc.transaction.Actor buildTransactionActor() {
        MerchantUser admin = currentAdmin();
        var builder = com.tms.report.grpc.transaction.Actor.newBuilder();
        if (admin != null) {
            builder.setId(String.valueOf(admin.getId()));
            builder.setName(admin.getName());
            builder.setEmail(admin.getEmail());
        } else {
            builder.setId("system").setName("System").setEmail("system@tms.local");
        }
        return builder.build();
    }

    private com.tms.report.grpc.notification.Actor buildNotificationActor() {
        MerchantUser admin = currentAdmin();
        var builder = com.tms.report.grpc.notification.Actor.newBuilder();
        if (admin != null) {
            builder.setId(String.valueOf(admin.getId()));
            builder.setName(admin.getName());
            builder.setEmail(admin.getEmail());
        } else {
            builder.setId("system").setName("System").setEmail("system@tms.local");
        }
        return builder.build();
    }

    private Actor buildDisputeActor() {
        MerchantUser admin = currentAdmin();
        var builder = Actor.newBuilder();
        if (admin != null) {
            builder.setId(String.valueOf(admin.getId()));
            builder.setName(admin.getName());
            builder.setEmail(admin.getEmail());
        } else {
            builder.setId("system").setName("System").setEmail("system@tms.local");
        }
        return builder.build();
    }

    private DataPlanRequest buildDataPlanRequest(Map<String, Object> data) {
        var builder = DataPlanRequest.newBuilder();
        if (data.containsKey("reference"))
            builder.setReference(str(data, "reference"));
        if (data.containsKey("sub_code"))
            builder.setSubCode(str(data, "sub_code"));
        if (data.containsKey("provider_sub_code"))
            builder.setProviderSubCode(str(data, "provider_sub_code"));
        if (data.containsKey("provider"))
            builder.setProvider(str(data, "provider"));
        if (data.containsKey("network"))
            builder.setNetwork(str(data, "network"));
        if (data.containsKey("amount"))
            builder.setAmount(dbl(data, "amount"));
        if (data.containsKey("cost"))
            builder.setCost(dbl(data, "cost"));
        if (data.containsKey("bundle"))
            builder.setBundle(str(data, "bundle"));
        if (data.containsKey("duration"))
            builder.setDuration(str(data, "duration"));
        if (data.containsKey("description"))
            builder.setDescription(str(data, "description"));
        if (data.containsKey("category"))
            builder.setCategory(str(data, "category"));
        return builder.build();
    }

    private MerchantUser currentAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof MerchantUserDetails details) {
            return details.getMerchantUser();
        }
        return null;
    }

    private String currentAdminName() {
        MerchantUser admin = currentAdmin();
        return admin != null ? admin.getName() : "System";
    }

}

package com.tms.report.modules.auth.service;

import com.tms.report.core.exception.AppException;
import com.tms.report.core.security.JwtService;
import com.tms.report.core.security.MerchantUserDetails;
import com.tms.report.modules.auth.dto.ActivateOtpRequest;
import com.tms.report.modules.auth.dto.ActivateRequest;
import com.tms.report.modules.auth.dto.ChangePasswordRequest;
import com.tms.report.modules.auth.dto.CrossLoginRequest;
import com.tms.report.modules.auth.dto.ForgotPasswordRequest;
import com.tms.report.modules.auth.dto.LoginRequest;
import com.tms.report.modules.auth.dto.LoginResponse;
import com.tms.report.modules.auth.dto.RequestActivationRequest;
import com.tms.report.modules.auth.dto.ResetPasswordRequest;
import com.tms.report.modules.auth.dto.UserData;
import com.tms.report.modules.auth.model.PasswordReset;
import com.tms.report.modules.auth.repository.PasswordResetRepository;
import com.tms.report.modules.grpc.service.GrpcClient;
import com.tms.report.modules.merchantuser.model.ActivationToken;
import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.merchantuser.repository.ActivationTokenRepository;
import com.tms.report.modules.merchantuser.repository.MerchantUserRepository;
import com.tms.report.modules.role.model.Privilege;
import com.tms.report.modules.role.model.Role;
import com.tms.report.modules.role.repository.PrivilegeRepository;
import com.tms.report.modules.role.repository.RoleRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication for the merchant dashboard: login/me/change-password,
 * forgot/reset, and account activation. Merchants are onboarded by document
 * upload (no password captured), so an account starts {@code pending} and is
 * activated — password set — via an emailed link OR an SMS/email OTP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final MerchantUserRepository merchantUserRepository;
    private final ActivationTokenRepository activationTokenRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final RoleRepository roleRepository;
    private final PrivilegeRepository privilegeRepository;
    private final JwtService jwtService;
    private final GrpcClient grpcClient;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RESET_TOKEN_EXPIRY_MINUTES = 60;
    private static final int OTP_EXPIRY_MINUTES = 15;
    private static final int LINK_EXPIRY_HOURS = 24;
    private static final int THROTTLE_SECONDS = 60;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Value("${mail.from-name:IRPay}")
    private String appName;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    // ── Login / session ─────────────────────────────────────

    /**
     * Unified login for the merchant dashboard. Delegates all authentication to
     * tms-user (Option 3: tms-user as single auth authority).
     *
     * <p>
     * The flow depends on whether the identifier matches a merchant owner or staff:
     * <ul>
     * <li><b>Owner</b> (merchant): Calls {@code AuthUser} gRPC to validate against
     * tms-user's users table. The merchant's password is stored in tms-user.</li>
     * <li><b>Staff</b> (operator): Calls {@code AuthOperator} gRPC to validate
     * against tms-user's operators table. Staff credentials are in operators.</li>
     * </ul>
     *
     * <p>
     * Roles and privileges are always managed locally in Merchant-Backend —
     * tms-user only handles credential validation.
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        String identifier = request.getEmail();
        String password = request.getPassword();

        if (identifier == null || identifier.isBlank() || password == null || password.isBlank()) {
            throw new AppException("Invalid credentials", HttpStatus.UNAUTHORIZED);
        }

        // Try staff (operator) authentication first — they login via email
        Map<String, Object> operatorResult = grpcClient.authOperator(identifier, password);
        if (Boolean.TRUE.equals(operatorResult.get("success"))) {
            return loginAsOperator(operatorResult);
        }

        // If not an operator, try merchant owner authentication
        Map<String, Object> userResult = grpcClient.authUser(identifier, password);
        if (Boolean.TRUE.equals(userResult.get("success"))) {
            return loginAsMerchantOwner(userResult);
        }

        // Both failed — return appropriate error
        String operatorReason = (String) operatorResult.get("reason");
        String userReason = (String) userResult.get("reason");

        // Check for specific error conditions
        if ("blocked".equals(userReason)) {
            throw new AppException("Your account has been blocked. Please contact support.", HttpStatus.FORBIDDEN);
        }
        if ("merchant_blocked".equals(operatorReason)) {
            throw new AppException("The merchant account has been blocked.", HttpStatus.FORBIDDEN);
        }
        if ("disabled".equals(operatorReason)) {
            throw new AppException("Your account has been disabled.", HttpStatus.FORBIDDEN);
        }
        if ("dashboard_not_enabled".equals(operatorReason)) {
            throw new AppException("Dashboard access is not enabled for this account.", HttpStatus.FORBIDDEN);
        }
        if ("not_merchant".equals(userReason)) {
            throw new AppException("Only merchants can access the merchant dashboard.", HttpStatus.FORBIDDEN);
        }

        // Generic invalid credentials
        throw new AppException("Invalid credentials", HttpStatus.UNAUTHORIZED);
    }

    /**
     * Complete login for a staff member (operator) after successful auth via
     * tms-user.
     */
    private LoginResponse loginAsOperator(Map<String, Object> authResult) {
        Long operatorId = ((Number) authResult.get("operator_id")).longValue();
        Long merchantUserId = ((Number) authResult.get("merchant_user_id")).longValue();
        String email = (String) authResult.get("email");
        String name = (String) authResult.get("name");
        String phoneNumber = (String) authResult.get("phone_number");

        // Find or create the MerchantUser record for this operator
        MerchantUser staffUser = findOrCreateStaffUser(operatorId, merchantUserId, email, name, phoneNumber);

        if (staffUser.isRevoked()) {
            throw new AppException("Your access has been revoked", HttpStatus.FORBIDDEN);
        }

        // Issue JWT
        String token = jwtService.generateToken(new MerchantUserDetails(staffUser));
        boolean verified = staffUser.getEmailVerifiedAt() != null;

        MerchantUserDetails details = new MerchantUserDetails(staffUser);
        List<String> privileges = details.getAuthorities().stream().map(a -> a.getAuthority()).sorted().toList();

        log.info("Staff login successful for operatorId={} merchantId={} email={}", operatorId, merchantUserId, email);

        return LoginResponse.builder().token(token).emailIsVerified(verified)
                .user(UserData.builder().name(staffUser.getName()).email(staffUser.getEmail()).role(staffUser.getRole())
                        .merchantId(staffUser.getMerchantId()).terminalId(staffUser.getTerminalId())
                        .emailIsVerified(verified).privileges(privileges).build())
                .build();
    }

    /**
     * Complete login for a merchant owner after successful auth via tms-user.
     */
    private LoginResponse loginAsMerchantOwner(Map<String, Object> authResult) {
        Long userId = ((Number) authResult.get("user_id")).longValue();
        String email = (String) authResult.get("email");
        String phoneNumber = (String) authResult.get("phone_number");
        String firstName = (String) authResult.get("first_name");
        String lastName = (String) authResult.get("last_name");
        String businessName = (String) authResult.get("business_name");

        String displayName = buildDisplayName(businessName, firstName, lastName, email);

        // Find or create the MerchantUser record
        MerchantUser merchantUser = findOrCreateMerchantUser(userId, email, phoneNumber, displayName);

        if (merchantUser.getRoleEntity() == null) {
            ensureDefaultRolesForMerchant(merchantUser);
        }

        if (merchantUser.isRevoked()) {
            throw new AppException("Your access has been revoked", HttpStatus.FORBIDDEN);
        }

        // Issue JWT
        String token = jwtService.generateToken(new MerchantUserDetails(merchantUser));
        boolean verified = merchantUser.getEmailVerifiedAt() != null;

        MerchantUserDetails details = new MerchantUserDetails(merchantUser);
        List<String> privileges = details.getAuthorities().stream().map(a -> a.getAuthority()).sorted().toList();

        log.info("Merchant owner login successful for merchantId={} email={}", userId, email);

        return LoginResponse.builder().token(token).emailIsVerified(verified)
                .user(UserData.builder().name(merchantUser.getName()).email(merchantUser.getEmail())
                        .role(merchantUser.getRole()).merchantId(merchantUser.getMerchantId())
                        .terminalId(merchantUser.getTerminalId()).emailIsVerified(verified).privileges(privileges)
                        .build())
                .build();
    }

    /**
     * Find or create a MerchantUser for a staff member (operator).
     */
    private MerchantUser findOrCreateStaffUser(Long operatorId, Long merchantUserId, String email, String name,
            String phoneNumber) {
        // Try to find by operatorId first
        return merchantUserRepository.findByOperatorId(operatorId).orElseGet(() -> {
            // Create new staff MerchantUser
            MerchantUser newUser = MerchantUser.builder().merchantId(merchantUserId).operatorId(operatorId)
                    .email(email != null && !email.isBlank() ? email.toLowerCase() : null)
                    .phoneNumber(phoneNumber != null && !phoneNumber.isBlank() ? phoneNumber : null)
                    .name(name != null && !name.isBlank() ? name : "Staff").role(MerchantUser.ROLE_CASHIER)
                    .status(MerchantUser.STATUS_ACTIVE).build();
            log.info("Creating MerchantUser for staff: operatorId={} merchantId={} email={}", operatorId,
                    merchantUserId, email);
            return merchantUserRepository.save(newUser);
        });
    }

    /**
     * Cross-system login for mobile-upgraded merchants. Validates credentials
     * against tms-user via gRPC, then finds or creates a MerchantUser record and
     * issues a local JWT.
     *
     * <p>
     * This allows merchants who were originally onboarded as ordinary users via the
     * mobile app (and later upgraded to merchant type) to sign into the merchant
     * dashboard using their tms-user credentials, without having to go through the
     * document-upload activation flow.
     */
    @Transactional
    public LoginResponse crossLogin(CrossLoginRequest request) {
        // 1. Validate credentials against tms-user via gRPC
        Map<String, Object> authResult = grpcClient.authUser(request.getIdentifier(), request.getPassword());

        if (!Boolean.TRUE.equals(authResult.get("success"))) {
            String reason = (String) authResult.get("reason");
            String message = (String) authResult.get("message");

            if ("blocked".equals(reason)) {
                throw new AppException(message != null ? message : "Your account has been blocked",
                        HttpStatus.FORBIDDEN);
            }
            if ("not_merchant".equals(reason)) {
                throw new AppException(message != null ? message : "Only merchants can access the merchant dashboard",
                        HttpStatus.FORBIDDEN);
            }
            // invalid_credentials or other errors
            throw new AppException("Invalid credentials", HttpStatus.UNAUTHORIZED);
        }

        // 2. Extract user data from auth result
        Long userId = ((Number) authResult.get("user_id")).longValue();
        String email = (String) authResult.get("email");
        String phoneNumber = (String) authResult.get("phone_number");
        String firstName = (String) authResult.get("first_name");
        String lastName = (String) authResult.get("last_name");
        String businessName = (String) authResult.get("business_name");

        // Build display name from available fields
        String displayName = buildDisplayName(businessName, firstName, lastName, email);

        // 3. Find or create the MerchantUser record
        MerchantUser merchantUser = findOrCreateMerchantUser(userId, email, phoneNumber, displayName);

        // 4. Ensure the user has a database-driven role assigned
        if (merchantUser.getRoleEntity() == null) {
            ensureDefaultRolesForMerchant(merchantUser);
        }

        // 5. Check access status
        if (merchantUser.isRevoked()) {
            throw new AppException("Your access to the merchant dashboard has been revoked", HttpStatus.FORBIDDEN);
        }

        // 6. Issue local JWT
        String token = jwtService.generateToken(new MerchantUserDetails(merchantUser));
        boolean verified = merchantUser.getEmailVerifiedAt() != null;

        // Resolve privilege codes from the role entity (or fallback)
        MerchantUserDetails details = new MerchantUserDetails(merchantUser);
        List<String> privileges = details.getAuthorities().stream().map(a -> a.getAuthority()).sorted().toList();

        log.info("Cross-login successful for merchantId={} email={}", userId, email);

        return LoginResponse.builder().token(token).emailIsVerified(verified)
                .user(UserData.builder().name(merchantUser.getName()).email(merchantUser.getEmail())
                        .role(merchantUser.getRole()).merchantId(merchantUser.getMerchantId())
                        .terminalId(merchantUser.getTerminalId()).emailIsVerified(verified).privileges(privileges)
                        .build())
                .build();
    }

    /**
     * Find an existing MerchantUser by merchantId (the tms-user users.id), or
     * create a new owner record for cross-login merchants.
     *
     * <p>
     * Lookup order: merchantId owner → email match → create new. If found by email,
     * the record is updated to link to the correct merchantId and promoted to owner
     * if necessary, avoiding duplicate key violations on the email unique
     * constraint.
     */
    private MerchantUser findOrCreateMerchantUser(Long merchantId, String email, String phoneNumber,
            String displayName) {
        String normalizedEmail = email != null && !email.isBlank() ? email.toLowerCase() : null;

        // 1. Try to find owner by merchantId first (most reliable for cross-login)
        Optional<MerchantUser> byMerchantId = merchantUserRepository.findByMerchantId(merchantId).stream()
                .filter(u -> MerchantUser.ROLE_OWNER.equalsIgnoreCase(u.getRole())).findFirst();
        if (byMerchantId.isPresent()) {
            return byMerchantId.get();
        }

        // 2. Check if a MerchantUser already exists with this email (avoid duplicate
        // key)
        if (normalizedEmail != null) {
            Optional<MerchantUser> byEmail = merchantUserRepository.findByEmail(normalizedEmail);
            if (byEmail.isPresent()) {
                MerchantUser existing = byEmail.get();
                // Update to link to the correct merchantId and promote to owner
                log.info("Found existing MerchantUser by email={}, updating merchantId from {} to {} and role to owner",
                        normalizedEmail, existing.getMerchantId(), merchantId);
                existing.setMerchantId(merchantId);
                existing.setRole(MerchantUser.ROLE_OWNER);
                existing.setStatus(MerchantUser.STATUS_ACTIVE);
                if (existing.getName() == null || existing.getName().isBlank()) {
                    existing.setName(displayName);
                }
                if (existing.getPhoneNumber() == null && phoneNumber != null && !phoneNumber.isBlank()) {
                    existing.setPhoneNumber(phoneNumber);
                }
                return merchantUserRepository.save(existing);
            }
        }

        // 3. Create new MerchantUser for this cross-login merchant
        MerchantUser newUser = MerchantUser.builder().merchantId(merchantId).email(normalizedEmail)
                .phoneNumber(phoneNumber != null && !phoneNumber.isBlank() ? phoneNumber : null).name(displayName)
                .role(MerchantUser.ROLE_OWNER).status(MerchantUser.STATUS_ACTIVE).build();
        log.info("Creating MerchantUser for cross-login: merchantId={} email={}", merchantId, normalizedEmail);
        return merchantUserRepository.save(newUser);
    }

    /**
     * Ensure database-driven roles exist for a merchant and assign the owner role.
     * Called on first cross-login when the MerchantUser has no roleEntity.
     */
    private void ensureDefaultRolesForMerchant(MerchantUser user) {
        Long merchantId = user.getMerchantId();

        // Find or create the owner role for this merchant
        Role ownerRole = roleRepository.findByMerchantIdAndSlug(merchantId, "owner").orElseGet(() -> {
            // Create default owner role with all privileges
            Set<Privilege> allPrivileges = new HashSet<>(privilegeRepository.findAll());
            Role role = Role.builder().merchantId(merchantId).name("Owner").slug("owner")
                    .description("Full access to all merchant features").systemRole(true).privileges(allPrivileges)
                    .build();
            log.info("Creating default owner role for merchantId={}", merchantId);
            return roleRepository.save(role);
        });

        // Also ensure cashier role exists (for future staff invites)
        if (!roleRepository.existsByMerchantIdAndSlug(merchantId, "cashier")) {
            Set<String> cashierPrivilegeCodes = Set.of("view_dashboard", "view_transaction", "manage_terminal");
            Set<Privilege> cashierPrivileges = new HashSet<>(privilegeRepository.findByCodeIn(cashierPrivilegeCodes));
            Role cashierRole = Role.builder().merchantId(merchantId).name("Cashier").slug("cashier")
                    .description("View-only access to transactions and terminals").systemRole(true)
                    .privileges(cashierPrivileges).build();
            log.info("Creating default cashier role for merchantId={}", merchantId);
            roleRepository.save(cashierRole);
        }

        // Assign owner role to the user
        user.setRoleEntity(ownerRole);
        merchantUserRepository.save(user);
        log.info("Assigned owner role to merchantUser id={} merchantId={}", user.getId(), merchantId);
    }

    /**
     * Build a display name from available profile data.
     */
    private String buildDisplayName(String businessName, String firstName, String lastName, String email) {
        if (businessName != null && !businessName.isBlank()) {
            return businessName;
        }
        if (firstName != null && !firstName.isBlank()) {
            if (lastName != null && !lastName.isBlank()) {
                return firstName + " " + lastName;
            }
            return firstName;
        }
        if (email != null && !email.isBlank()) {
            return email.split("@")[0];
        }
        return "Merchant";
    }

    public MerchantUser me() {
        return getCurrentMerchantUser();
    }

    public MerchantUser getCurrentMerchantUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof MerchantUserDetails details) {
            return details.getMerchantUser();
        }
        throw new AppException("Not authenticated", HttpStatus.UNAUTHORIZED);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        MerchantUser user = getCurrentMerchantUser();

        // Get the user's identifier (email or phone) for auth verification
        String identifier = user.getEmail();
        if (identifier == null || identifier.isBlank()) {
            identifier = user.getPhoneNumber();
        }
        if (identifier == null || identifier.isBlank()) {
            throw new AppException("No email or phone on account", HttpStatus.BAD_REQUEST);
        }

        // Verify current password via tms-user
        Map<String, Object> authResult = grpcClient.authUser(identifier, request.getCurrentPassword());
        if (!Boolean.TRUE.equals(authResult.get("success"))) {
            throw new AppException("Current password is incorrect", HttpStatus.BAD_REQUEST);
        }

        // Set new password in tms-user
        boolean isEmail = EMAIL_PATTERN.matcher(identifier).matches();
        setPasswordInTmsUser(user, identifier, isEmail, request.getNewPassword());

        log.info("Password changed for user: email={} merchantId={}", user.getEmail(), user.getMerchantId());
    }

    // ── Activation (link + OTP) ─────────────────────────────

    /**
     * Issue an activation challenge to a pending account via the requested channel.
     * Idempotent-ish: any prior challenges for the account are cleared.
     */
    @Transactional
    public void requestActivation(RequestActivationRequest request) {
        MerchantUser user = findByIdentifier(request.getIdentifier())
                .orElseThrow(() -> new AppException("No account found for this identifier", HttpStatus.NOT_FOUND));
        if (user.isRevoked()) {
            throw new AppException("Your access has been revoked", HttpStatus.FORBIDDEN);
        }
        if (user.isActive()) {
            // Already activated - they should use forgot-password flow instead
            throw new AppException("This account is already activated. Use forgot-password instead.",
                    HttpStatus.CONFLICT);
        }
        initiateActivation(user, request.getChannel());
    }

    /** Complete activation via the emailed link token. */
    @Transactional
    public void activate(ActivateRequest request) {
        ActivationToken token = activationTokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new AppException("Invalid or expired activation link", HttpStatus.BAD_REQUEST));
        if (!token.isUsable()) {
            throw new AppException("Invalid or expired activation link", HttpStatus.BAD_REQUEST);
        }
        completeActivation(token, request.getPassword());
    }

    /** Complete activation via the OTP channel. */
    @Transactional
    public void activateOtp(ActivateOtpRequest request) {
        MerchantUser user = findByIdentifier(request.getIdentifier())
                .orElseThrow(() -> new AppException("Invalid or expired code", HttpStatus.BAD_REQUEST));
        ActivationToken token = activationTokenRepository
                .findTopByMerchantUserIdAndOtpOrderByCreatedAtDesc(user.getId(), request.getOtp().trim())
                .orElseThrow(() -> new AppException("Invalid or expired code", HttpStatus.BAD_REQUEST));
        if (!token.isUsable()) {
            throw new AppException("Invalid or expired code", HttpStatus.BAD_REQUEST);
        }
        completeActivation(token, request.getPassword());
    }

    private void completeActivation(ActivationToken token, String rawPassword) {
        MerchantUser user = merchantUserRepository.findById(token.getMerchantUserId())
                .orElseThrow(() -> new AppException("Account not found", HttpStatus.BAD_REQUEST));

        // Determine identifier for tms-user lookup (prefer email, fallback to phone)
        String identifier = user.getEmail();
        boolean isEmail = true;
        if (identifier == null || identifier.isBlank()) {
            identifier = user.getPhoneNumber();
            isEmail = false;
        }

        // Set password in tms-user (the single source of truth for credentials)
        setPasswordInTmsUser(user, identifier, isEmail, rawPassword);

        // Update local record status (no password stored locally)
        user.setStatus(MerchantUser.STATUS_ACTIVE);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(LocalDateTime.now());
        }
        merchantUserRepository.save(user);

        token.setConsumedAt(LocalDateTime.now());
        activationTokenRepository.save(token);

        log.info("Activation completed for user: email={} merchantId={}", user.getEmail(), user.getMerchantId());
    }

    private void initiateActivation(MerchantUser user, String channel) {
        activationTokenRepository.deleteByMerchantUserId(user.getId());
        String ch = channel == null ? "" : channel.trim().toLowerCase();

        if (ActivationToken.CHANNEL_LINK.equals(ch)) {
            String token = UUID.randomUUID().toString().replace("-", "");
            activationTokenRepository.save(ActivationToken.builder().merchantUserId(user.getId()).token(token)
                    .channel(ActivationToken.CHANNEL_LINK).expiresAt(LocalDateTime.now().plusHours(LINK_EXPIRY_HOURS))
                    .build());
            sendActivationLink(user, token);
        } else if (ActivationToken.CHANNEL_OTP.equals(ch)) {
            String otp = generateOtp();
            activationTokenRepository.save(
                    ActivationToken.builder().merchantUserId(user.getId()).otp(otp).channel(ActivationToken.CHANNEL_OTP)
                            .expiresAt(LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES)).build());
            sendActivationOtp(user, otp);
        } else {
            throw new AppException("channel must be 'link' or 'otp'", HttpStatus.BAD_REQUEST);
        }
    }

    private void sendActivationLink(MerchantUser user, String token) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new AppException("No email on file for this account. Use the OTP channel instead.",
                    HttpStatus.BAD_REQUEST);
        }
        String link = frontendUrl + "/activate?token=" + token;
        Map<String, String> data = new HashMap<>();
        data.put("name", user.getName() != null ? user.getName() : "there");
        data.put("link", link);
        data.put("ttl", String.valueOf(LINK_EXPIRY_HOURS) + " hours");
        requireSent(safeSendEmail(user.getEmail(), appName + " - Activate your account", "activate-account", data),
                "email");
    }

    private void sendActivationOtp(MerchantUser user, String otp) {
        String phone = user.getPhoneNumber();
        if (phone != null && !phone.isBlank()) {
            String message = appName + ": Your activation code is " + otp + ". Do not share it with anyone.";
            requireSent(safeSendSms(phone, message), "SMS");
        } else if (user.getEmail() != null && !user.getEmail().isBlank()) {
            Map<String, String> data = new HashMap<>();
            data.put("name", user.getName() != null ? user.getName() : "there");
            data.put("token", otp);
            data.put("ttl", String.valueOf(OTP_EXPIRY_MINUTES));
            requireSent(safeSendEmail(user.getEmail(), appName + " - Activation code", "activate-otp", data), "email");
        } else {
            throw new AppException("No phone or email on file for this account.", HttpStatus.BAD_REQUEST);
        }
    }

    // ── Forgot / reset password (reuses password_resets) ────

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String identifier = request.getIdentifier().trim().toLowerCase();
        boolean isEmail = EMAIL_PATTERN.matcher(identifier).matches();

        // Try to find the user locally first
        MerchantUser user = findByIdentifier(identifier).orElse(null);

        // If not found locally, check if they exist in tms-user (TID-uploaded or
        // mobile-upgraded merchants)
        if (user == null) {
            user = findOrCreateFromTmsUser(identifier, isEmail);
        }

        if (user == null) {
            throw new AppException("No account found with this " + (isEmail ? "email" : "phone number"),
                    HttpStatus.NOT_FOUND);
        }

        String resetKey = user.getEmail();
        if (resetKey == null || resetKey.isBlank()) {
            // User has no email — use phone number as key if available
            resetKey = user.getPhoneNumber();
            if (resetKey == null || resetKey.isBlank()) {
                throw new AppException("No email or phone number on file for password reset", HttpStatus.BAD_REQUEST);
            }
        }

        passwordResetRepository.findTopByEmailOrderByCreatedAtDesc(resetKey).ifPresent(existing -> {
            if (existing.getCreatedAt() != null
                    && existing.getCreatedAt().plusSeconds(THROTTLE_SECONDS).isAfter(LocalDateTime.now())) {
                throw new AppException("Please wait before requesting another reset code",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
        });
        passwordResetRepository.deleteByEmail(resetKey);

        String otp = generateOtp();
        passwordResetRepository.save(PasswordReset.builder().email(resetKey).token(otp)
                .expiresAt(LocalDateTime.now().plusMinutes(RESET_TOKEN_EXPIRY_MINUTES)).build());

        if (isEmail) {
            Map<String, String> data = new HashMap<>();
            data.put("name", user.getName() != null ? user.getName() : "there");
            data.put("token", otp);
            data.put("ttl", String.valueOf(RESET_TOKEN_EXPIRY_MINUTES));
            requireSent(safeSendEmail(user.getEmail(), appName + " - Password Reset", "reset-password", data), "email");
        } else {
            String message = appName + ": Your password reset code is " + otp + ". Do not share it with anyone.";
            requireSent(safeSendSms(user.getPhoneNumber(), message), "SMS");
        }
    }

    /**
     * Look up a user in tms-user by identifier and create a MerchantUser record if
     * they exist and are a merchant. Used for TID-uploaded merchants and
     * mobile-upgraded merchants who haven't logged into the dashboard yet.
     *
     * @return the created MerchantUser, or null if not found or not a merchant
     */
    private MerchantUser findOrCreateFromTmsUser(String identifier, boolean isEmail) {
        try {
            // Find the user ID in tms-user via gRPC
            Long userId = findUserIdInTmsUser(identifier, isEmail);
            if (userId == null) {
                return null;
            }

            // Get profile data from tms-user via gRPC (includes type)
            Map<String, Object> profile = grpcClient.getUserProfile(userId);
            String userType = (String) profile.get("type");
            if (!"merchant".equalsIgnoreCase(userType)) {
                log.debug("User {} exists but is type={}, not merchant", identifier, userType);
                return null;
            }

            // Build display name from profile data
            String email = (String) profile.get("email");
            String phoneNumber = (String) profile.get("phone_number");
            String businessName = (String) profile.get("business_name");
            String firstName = (String) profile.get("first_name");
            String lastName = (String) profile.get("last_name");
            String displayName = buildDisplayName(businessName, firstName, lastName, email);

            // Create the MerchantUser record
            MerchantUser newUser = MerchantUser.builder().merchantId(userId)
                    .email(email != null && !email.isBlank() ? email.toLowerCase() : null)
                    .phoneNumber(phoneNumber != null && !phoneNumber.isBlank() ? phoneNumber : null).name(displayName)
                    .role(MerchantUser.ROLE_OWNER).status(MerchantUser.STATUS_PENDING).build();

            log.info("Creating MerchantUser from tms-user for forgot-password: merchantId={} email={}", userId, email);
            MerchantUser saved = merchantUserRepository.save(newUser);

            // Seed default roles
            ensureDefaultRolesForMerchant(saved);

            return saved;
        } catch (Exception e) {
            log.warn("Failed to find/create user from tms-user for identifier={}: {}", identifier, e.getMessage());
            return null;
        }
    }

    /**
     * Find a user ID in tms-user by email or phone via gRPC.
     */
    private Long findUserIdInTmsUser(String identifier, boolean isEmail) {
        try {
            log.info("findUserIdInTmsUser: looking up {} isEmail={}", identifier, isEmail);
            Map<String, Object> result = isEmail
                    ? grpcClient.findUserByEmail(identifier.toLowerCase())
                    : grpcClient.findUserByPhoneNumber(identifier);
            log.info("findUserIdInTmsUser: result={}", result);
            if (Boolean.TRUE.equals(result.get("exists"))) {
                return ((Number) result.get("user_id")).longValue();
            }
            log.warn("findUserIdInTmsUser: user not found for identifier={}", identifier);
            return null;
        } catch (Exception e) {
            log.error("findUserIdInTmsUser: gRPC error for identifier={}: {}", identifier, e.getMessage(), e);
            return null;
        }
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String identifier = request.getIdentifier().trim();
        boolean isEmail = EMAIL_PATTERN.matcher(identifier).matches();

        MerchantUser user = findByIdentifier(identifier)
                .orElseThrow(() -> new AppException("Invalid or expired reset code", HttpStatus.BAD_REQUEST));

        // The token is keyed by email if available, otherwise by phone number
        // (matching the logic in forgotPassword)
        String resetKey = user.getEmail();
        if (resetKey == null || resetKey.isBlank()) {
            resetKey = user.getPhoneNumber();
        }

        PasswordReset reset = passwordResetRepository.findByEmailAndToken(resetKey, request.getToken())
                .orElseThrow(() -> new AppException("Invalid or expired reset code", HttpStatus.BAD_REQUEST));
        if (reset.isExpired()) {
            passwordResetRepository.delete(reset);
            throw new AppException("Invalid or expired reset code", HttpStatus.BAD_REQUEST);
        }

        String rawPassword = request.getPassword();

        // Set password in tms-user using the identifier from the request
        // This ensures we look up by the same email/phone the user entered
        setPasswordInTmsUser(user, identifier, isEmail, rawPassword);

        // Update local record status (no password stored locally)
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(LocalDateTime.now());
        }
        if (MerchantUser.STATUS_PENDING.equalsIgnoreCase(user.getStatus())) {
            user.setStatus(MerchantUser.STATUS_ACTIVE);
        }
        merchantUserRepository.save(user);
        passwordResetRepository.delete(reset);

        log.info("Password reset completed for user: email={} merchantId={}", user.getEmail(), user.getMerchantId());
    }

    // ── helpers ─────────────────────────────────────────────

    private java.util.Optional<MerchantUser> findByIdentifier(String identifier) {
        String id = identifier == null ? "" : identifier.trim();
        return merchantUserRepository.findByEmail(id.toLowerCase())
                .or(() -> merchantUserRepository.findByPhoneNumber(id));
    }

    private Map<String, Object> safeSendEmail(String to, String subject, String template, Map<String, String> data) {
        try {
            return grpcClient.sendEmail(to, subject, template, data);
        } catch (Exception e) {
            log.error("Activation/reset email to {} failed: {}", to, e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    private Map<String, Object> safeSendSms(String phone, String message) {
        try {
            return grpcClient.sendSms(phone, message);
        } catch (Exception e) {
            log.error("Activation/reset SMS to {} failed: {}", phone, e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    private void requireSent(Map<String, Object> result, String channel) {
        if (!Boolean.TRUE.equals(result.get("success"))) {
            throw new AppException("Failed to send " + channel + ". Please try again or use a different channel.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String generateOtp() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /**
     * Sync a user's password to tms-user so they can login to both the merchant
     * dashboard and POS terminal with the same credentials. Called after password
     * reset or account activation.
     *
     * @param user
     *            the MerchantUser whose password is being set
     * @param identifier
     *            the email or phone number to look up in tms-user
     * @param isEmail
     *            true if identifier is an email, false if phone number
     * @param rawPassword
     *            the plain-text password to set
     * @throws AppException
     *             if the password cannot be set in tms-user
     */
    private void setPasswordInTmsUser(MerchantUser user, String identifier, boolean isEmail, String rawPassword) {
        // Always look up in tms-user using the identifier directly
        // Don't trust merchantId on local record - it may be stale or wrong
        Long userId = findUserIdInTmsUser(identifier, isEmail);

        if (userId == null) {
            log.error("setPasswordInTmsUser: could not find user in tms-user for identifier={}", identifier);
            throw new AppException("User not found in authentication system", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        try {
            Map<String, Object> result = grpcClient.setUserPassword(userId, rawPassword);
            if (Boolean.TRUE.equals(result.get("success"))) {
                log.info("setPasswordInTmsUser: password set for userId={}", userId);
                // Update merchantId on local record if it differs
                if (user.getMerchantId() == null || !user.getMerchantId().equals(userId)) {
                    log.info("setPasswordInTmsUser: updating merchantId from {} to {} on MerchantUser id={}",
                            user.getMerchantId(), userId, user.getId());
                    user.setMerchantId(userId);
                }
            } else {
                String reason = (String) result.get("reason");
                String message = (String) result.get("message");
                log.error("setPasswordInTmsUser: failed for userId={}: {} - {}", userId, reason, message);
                throw new AppException(message != null ? message : "Failed to set password",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("setPasswordInTmsUser: exception for userId={}: {}", userId, e.getMessage());
            throw new AppException("Failed to set password: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}

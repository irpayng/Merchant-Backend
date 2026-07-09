package com.tms.report.modules.auth.service;

import com.tms.report.core.exception.AppException;
import com.tms.report.core.security.JwtService;
import com.tms.report.core.security.MerchantUserDetails;
import com.tms.report.modules.auth.dto.ActivateOtpRequest;
import com.tms.report.modules.auth.dto.ActivateRequest;
import com.tms.report.modules.auth.dto.ChangePasswordRequest;
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
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
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

    public LoginResponse login(LoginRequest request) {
        MerchantUser user = findByIdentifier(request.getEmail())
                .orElseThrow(() -> new AppException("Invalid credentials", HttpStatus.UNAUTHORIZED));

        if (user.isRevoked()) {
            throw new AppException("Your access has been revoked", HttpStatus.UNAUTHORIZED);
        }
        if (!user.isActive() || user.getPassword() == null) {
            throw new AppException("Account not activated. Please set up your password to continue.",
                    HttpStatus.FORBIDDEN);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new AppException("Invalid credentials", HttpStatus.UNAUTHORIZED);
        }

        String token = jwtService.generateToken(new MerchantUserDetails(user));
        boolean verified = user.getEmailVerifiedAt() != null;

        // Resolve privilege codes from the role entity (or fallback)
        MerchantUserDetails details = new MerchantUserDetails(user);
        List<String> privileges = details.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .sorted()
                .toList();

        return LoginResponse.builder().token(token).emailIsVerified(verified)
                .user(UserData.builder().name(user.getName()).email(user.getEmail()).role(user.getRole())
                        .merchantId(user.getMerchantId()).terminalId(user.getTerminalId()).emailIsVerified(verified)
                        .privileges(privileges)
                        .build())
                .build();
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
        if (user.getPassword() == null
                || !passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new AppException("Current password is incorrect", HttpStatus.BAD_REQUEST);
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        merchantUserRepository.save(user);
    }

    // ── Activation (link + OTP) ─────────────────────────────

    /**
     * Issue an activation challenge to a pending account via the requested
     * channel. Idempotent-ish: any prior challenges for the account are cleared.
     */
    @Transactional
    public void requestActivation(RequestActivationRequest request) {
        MerchantUser user = findByIdentifier(request.getIdentifier())
                .orElseThrow(() -> new AppException("No account found for this identifier", HttpStatus.NOT_FOUND));
        if (user.isRevoked()) {
            throw new AppException("Your access has been revoked", HttpStatus.FORBIDDEN);
        }
        if (user.isActive() && user.getPassword() != null) {
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
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setStatus(MerchantUser.STATUS_ACTIVE);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(LocalDateTime.now());
        }
        merchantUserRepository.save(user);

        token.setConsumedAt(LocalDateTime.now());
        activationTokenRepository.save(token);
    }

    private void initiateActivation(MerchantUser user, String channel) {
        activationTokenRepository.deleteByMerchantUserId(user.getId());
        String ch = channel == null ? "" : channel.trim().toLowerCase();

        if (ActivationToken.CHANNEL_LINK.equals(ch)) {
            String token = UUID.randomUUID().toString().replace("-", "");
            activationTokenRepository.save(ActivationToken.builder().merchantUserId(user.getId()).token(token)
                    .channel(ActivationToken.CHANNEL_LINK)
                    .expiresAt(LocalDateTime.now().plusHours(LINK_EXPIRY_HOURS)).build());
            sendActivationLink(user, token);
        } else if (ActivationToken.CHANNEL_OTP.equals(ch)) {
            String otp = generateOtp();
            activationTokenRepository.save(ActivationToken.builder().merchantUserId(user.getId()).otp(otp)
                    .channel(ActivationToken.CHANNEL_OTP)
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
        MerchantUser user = findByIdentifier(identifier)
                .orElseThrow(() -> new AppException("No account found with this "
                        + (isEmail ? "email" : "phone number"), HttpStatus.NOT_FOUND));

        String resetKey = user.getEmail();
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

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        MerchantUser user = findByIdentifier(request.getIdentifier())
                .orElseThrow(() -> new AppException("Invalid or expired reset code", HttpStatus.BAD_REQUEST));
        PasswordReset reset = passwordResetRepository.findByEmailAndToken(user.getEmail(), request.getToken())
                .orElseThrow(() -> new AppException("Invalid or expired reset code", HttpStatus.BAD_REQUEST));
        if (reset.isExpired()) {
            passwordResetRepository.delete(reset);
            throw new AppException("Invalid or expired reset code", HttpStatus.BAD_REQUEST);
        }
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(LocalDateTime.now());
        }
        if (MerchantUser.STATUS_PENDING.equalsIgnoreCase(user.getStatus())) {
            user.setStatus(MerchantUser.STATUS_ACTIVE);
        }
        merchantUserRepository.save(user);
        passwordResetRepository.delete(reset);
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
}

package com.tms.report.modules.auth.service;

import com.tms.report.core.exception.AppException;
import com.tms.report.core.security.AdminDetails;
import com.tms.report.core.security.JwtService;
import com.tms.report.modules.admin.model.Admin;
import com.tms.report.modules.admin.repository.AdminRepository;
import com.tms.report.modules.auth.dto.ChangePasswordRequest;
import com.tms.report.modules.auth.dto.ForgotPasswordRequest;
import com.tms.report.modules.auth.dto.LoginRequest;
import com.tms.report.modules.auth.dto.LoginResponse;
import com.tms.report.modules.auth.dto.ResetPasswordRequest;
import com.tms.report.modules.auth.dto.RoleData;
import com.tms.report.modules.auth.dto.UserData;
import com.tms.report.modules.auth.model.PasswordReset;
import com.tms.report.modules.auth.repository.PasswordResetRepository;
import com.tms.report.modules.grpc.service.GrpcClient;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AdminRepository adminRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final GrpcClient grpcClient;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_EXPIRY_MINUTES = 60;
    private static final int THROTTLE_SECONDS = 60;

    /**
     * Matches common email patterns. Used to distinguish email from phone number
     * input.
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Value("${mail.from-name:IRPay}")
    private String appName;

    public LoginResponse login(LoginRequest request) {
        Admin admin = adminRepository.findByEmail(request.getEmail().toLowerCase())
                .orElseThrow(() -> new AppException("Invalid credentials", HttpStatus.UNAUTHORIZED));

        if (admin.isBlocked()) {
            throw new AppException("Account is blocked", HttpStatus.UNAUTHORIZED);
        }

        if (!passwordEncoder.matches(request.getPassword(), admin.getPassword())) {
            throw new AppException("Invalid credentials", HttpStatus.UNAUTHORIZED);
        }

        AdminDetails adminDetails = new AdminDetails(admin);
        String token = jwtService.generateToken(adminDetails);

        boolean verified = admin.getEmailVerifiedAt() != null;

        List<RoleData> roles = admin.getRoles().stream().map(role -> RoleData.builder().code(role.getCode())
                .privileges(role.getPrivileges().stream().map(p -> p.getCode()).toList()).build()).toList();

        return LoginResponse.builder().token(token).emailIsVerified(verified).user(UserData.builder()
                .name(admin.getName()).email(admin.getEmail()).emailIsVerified(verified).roles(roles).build()).build();
    }

    public Admin me() {
        return getCurrentAdmin();
    }

    public void changePassword(ChangePasswordRequest request) {
        Admin admin = getCurrentAdmin();

        if (!passwordEncoder.matches(request.getCurrentPassword(), admin.getPassword())) {
            throw new AppException("Current password is incorrect", HttpStatus.BAD_REQUEST);
        }

        admin.setPassword(passwordEncoder.encode(request.getNewPassword()));
        adminRepository.save(admin);
    }

    public Admin getCurrentAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AdminDetails details) {
            return details.getAdmin();
        }
        throw new AppException("Not authenticated", HttpStatus.UNAUTHORIZED);
    }

    /**
     * Initiate password reset. Accepts either an email address or phone number.
     * Both channels are delivered through the notification service gRPC so the real
     * provider stack (ZeptoMail for email, Termii for SMS in staging) is exercised
     * — mirrors the user service's reset-password flow.
     */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String identifier = request.getIdentifier().trim().toLowerCase();
        boolean isEmail = EMAIL_PATTERN.matcher(identifier).matches();

        Admin admin;
        if (isEmail) {
            admin = adminRepository.findByEmail(identifier).orElse(null);
        } else {
            admin = adminRepository.findByPhoneNumber(identifier).orElse(null);
        }

        if (admin == null) {
            String type = isEmail ? "email" : "phone number";
            throw new AppException("No account found with this " + type, HttpStatus.NOT_FOUND);
        }

        // Use email as the canonical key for the password_resets table
        String resetKey = admin.getEmail();

        // Throttle: check if a token was created within the last 60 seconds
        passwordResetRepository.findTopByEmailOrderByCreatedAtDesc(resetKey).ifPresent(existing -> {
            if (existing.getCreatedAt() != null
                    && existing.getCreatedAt().plusSeconds(THROTTLE_SECONDS).isAfter(LocalDateTime.now())) {
                throw new AppException("Please wait before requesting another reset token",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
        });

        // Remove any existing reset tokens for this admin
        passwordResetRepository.deleteByEmail(resetKey);

        String token = generateOtp();
        PasswordReset reset = PasswordReset.builder().email(resetKey).token(token)
                .expiresAt(LocalDateTime.now().plusMinutes(TOKEN_EXPIRY_MINUTES)).build();
        passwordResetRepository.save(reset);

        // Send OTP via the channel matching the identifier type
        if (isEmail) {
            sendOtpViaEmail(admin, token);
        } else {
            sendOtpViaSms(admin, token);
        }
    }

    private void sendOtpViaEmail(Admin admin, String token) {
        Map<String, String> templateData = new HashMap<>();
        templateData.put("name", admin.getName() != null ? admin.getName() : "Admin");
        templateData.put("token", token);
        templateData.put("ttl", String.valueOf(TOKEN_EXPIRY_MINUTES));

        try {
            Map<String, Object> result = grpcClient.sendEmail(admin.getEmail(), appName + " - Password Reset",
                    "reset-password", templateData);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                String msg = String.valueOf(result.get("message"));
                log.error("Notification service rejected reset email for {}: {}", admin.getEmail(), msg);
                throw new AppException("Failed to send email. Please try again or use your phone number.",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
            log.info("Password reset OTP sent via email to admin: {}", admin.getEmail());
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", admin.getEmail(), e.getMessage());
            throw new AppException("Failed to send email. Please try again or use your phone number.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void sendOtpViaSms(Admin admin, String token) {
        String phone = admin.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            log.warn("Admin {} has no phone number, cannot send SMS OTP", admin.getEmail());
            throw new AppException("No phone number on file for this account. Please use your email.",
                    HttpStatus.BAD_REQUEST);
        }
        try {
            String message = appName + ": Your password reset code is " + token + ". Do not share this with anyone.";
            Map<String, Object> result = grpcClient.sendSms(phone, message);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                String msg = String.valueOf(result.get("message"));
                log.error("Notification service rejected reset SMS for {}: {}", admin.getEmail(), msg);
                throw new AppException("Failed to send SMS. Please try again or use your email.",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
            log.info("Password reset OTP sent via SMS to admin: {}", admin.getEmail());
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to send password reset SMS to {}: {}", admin.getEmail(), e.getMessage());
            throw new AppException("Failed to send SMS. Please try again or use your email.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String identifier = request.getIdentifier().trim().toLowerCase();
        boolean isEmail = EMAIL_PATTERN.matcher(identifier).matches();

        Admin admin;
        if (isEmail) {
            admin = adminRepository.findByEmail(identifier)
                    .orElseThrow(() -> new AppException("Invalid or expired reset token", HttpStatus.BAD_REQUEST));
        } else {
            admin = adminRepository.findByPhoneNumber(identifier)
                    .orElseThrow(() -> new AppException("Invalid or expired reset token", HttpStatus.BAD_REQUEST));
        }

        // Look up the reset token using the admin's email (canonical key)
        PasswordReset reset = passwordResetRepository.findByEmailAndToken(admin.getEmail(), request.getToken())
                .orElseThrow(() -> new AppException("Invalid or expired reset token", HttpStatus.BAD_REQUEST));

        if (reset.isExpired()) {
            passwordResetRepository.delete(reset);
            throw new AppException("Invalid or expired reset token", HttpStatus.BAD_REQUEST);
        }

        admin.setPassword(passwordEncoder.encode(request.getPassword()));

        // Mark email as verified on password reset (matching PHP behavior)
        if (admin.getEmailVerifiedAt() == null) {
            admin.setEmailVerifiedAt(LocalDateTime.now());
        }

        adminRepository.save(admin);
        passwordResetRepository.delete(reset);
    }

    private String generateOtp() {
        return String.format("%04d", RANDOM.nextInt(10000));
    }
}

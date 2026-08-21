package com.tms.report.modules.merchantuser.service;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.merchantuser.repository.MerchantUserRepository;
import com.tms.report.modules.user.model.Profile;
import com.tms.report.modules.user.model.User;
import com.tms.report.modules.user.repository.ProfileRepository;
import com.tms.report.modules.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the merchant dashboard owner login for a merchant.
 *
 * <p>
 * A merchant is promoted in the kyc service (business application approved) or
 * provisioned by config from a TID upload — neither of those flows knows
 * anything about {@code merchant.merchant_users}, which is this service's own
 * table. Until this existed nothing ever created that row, so an applicant who
 * had just been told "your merchant application has been approved" had no
 * dashboard account at all: login answered "invalid credentials" and
 * request-activation answered "no account found for this identifier", with no
 * way for them or support to get in.
 *
 * <p>
 * The row is created {@code pending} with no password, matching the rest of the
 * activation design — the merchant sets their dashboard password from the
 * emailed link or the OTP challenge. It is deliberately a separate credential
 * from their mobile app password, which lives in user-service and is never
 * copied here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantProvisioningService {

    private final MerchantUserRepository merchantUserRepository;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;

    /**
     * Ensure a merchant has an owner login.
     *
     * @param merchantId
     *            the merchant's {@code users.id}
     * @return the id of the login that was created, or empty when one already
     *         existed or the merchant could not be provisioned (unknown user, no
     *         email and no phone, identifier already used by another login)
     */
    @Transactional
    public Optional<Long> provisionOwner(long merchantId) {
        List<MerchantUser> existing = merchantUserRepository.findByMerchantId(merchantId);
        if (!existing.isEmpty()) {
            log.debug("Merchant {} already has {} dashboard login(s) — skipping provisioning", merchantId,
                    existing.size());
            return Optional.empty();
        }

        User user = userRepository.findById(merchantId).orElse(null);
        if (user == null) {
            // The replicated users row has not landed yet, or the id is bogus.
            // Logical replication is usually sub-second behind; the caller logs
            // and the backfill picks this up on the next start.
            log.warn("Cannot provision merchant dashboard login: no replicated user row for merchant_id={}",
                    merchantId);
            return Optional.empty();
        }

        String email = normaliseEmail(user.getEmail());
        String phone = trimToNull(user.getPhoneNumber());
        if (email == null && phone == null) {
            log.warn("Cannot provision merchant dashboard login for merchant_id={}: no email or phone on file",
                    merchantId);
            return Optional.empty();
        }

        // merchant_users.email is unique and phone is the alternate login
        // identifier, so refuse rather than collide when either is already taken
        // (e.g. the person is a cashier on another merchant's dashboard).
        if (email != null && merchantUserRepository.findByEmail(email).isPresent()) {
            log.warn("Cannot provision merchant dashboard login for merchant_id={}: email already used by another "
                    + "login", merchantId);
            return Optional.empty();
        }
        if (phone != null && merchantUserRepository.findByPhoneNumber(phone).isPresent()) {
            log.warn("Cannot provision merchant dashboard login for merchant_id={}: phone number already used by "
                    + "another login", merchantId);
            return Optional.empty();
        }

        MerchantUser owner = merchantUserRepository.save(
                MerchantUser.builder().merchantId(merchantId).role(MerchantUser.ROLE_OWNER).name(resolveName(user))
                        .email(email).phoneNumber(phone).status(MerchantUser.STATUS_PENDING).build());

        log.info("Provisioned merchant dashboard owner login id={} for merchant_id={}", owner.getId(), merchantId);
        return Optional.of(owner.getId());
    }

    /**
     * Display name for the dashboard: the registered business name the merchant
     * approval stamped on the user, then their personal name, then the email.
     */
    private String resolveName(User user) {
        String business = normaliseName(user.getBusinessName());
        if (business != null) {
            return business;
        }
        Profile profile = profileRepository.findByUserId(user.getId()).orElse(null);
        if (profile != null) {
            String personal = normaliseName(
                    (blankToEmpty(profile.getFirstName()) + " " + blankToEmpty(profile.getLastName())));
            if (personal != null) {
                return personal;
            }
        }
        return user.getEmail() != null ? user.getEmail() : "Merchant";
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String normaliseName(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Emails are stored and looked up lowercased, matching the login lookup. */
    private String normaliseEmail(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

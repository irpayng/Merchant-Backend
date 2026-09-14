package com.tms.report.core.command;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.merchantuser.repository.MerchantUserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds a development merchant owner so the dashboard is loginable locally. In
 * real use, accounts are provisioned from onboarding and activated via link or
 * OTP — there is no global admin. Idempotent; runs on every startup.
 *
 * <p>
 * Note: Passwords are stored in tms-user, not locally. The seeded user must
 * also exist in tms-user with a password set for login to work.
 */
@Slf4j
@Component
@Order(1)
@Profile("!test")
@RequiredArgsConstructor
public class AppSeedCommand implements CommandLineRunner {

    private final MerchantUserRepository merchantUserRepository;

    /** The replicated merchant (users.id) the dev owner login is bound to. */
    @Value("${app.seed.merchant-id:1}")
    private Long seedMerchantId;

    @Value("${app.seed.owner-email:admin@irpay.ng}")
    private String seedOwnerEmail;

    @Override
    public void run(String... args) {
        String email = seedOwnerEmail.toLowerCase();
        if (merchantUserRepository.findByEmail(email).isPresent()) {
            return;
        }
        // No local password - authentication is handled by tms-user
        merchantUserRepository
                .save(MerchantUser.builder().merchantId(seedMerchantId).role(MerchantUser.ROLE_OWNER).name("Demo Owner")
                        .email(email).status(MerchantUser.STATUS_ACTIVE).emailVerifiedAt(LocalDateTime.now()).build());
        log.info("Seeded dev merchant owner {} (merchant_id={})", email, seedMerchantId);
    }
}

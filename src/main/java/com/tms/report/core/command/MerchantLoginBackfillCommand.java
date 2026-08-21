package com.tms.report.core.command;

import com.tms.report.modules.merchantuser.service.MerchantProvisioningService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the missing dashboard owner logins for merchants that were approved
 * before
 * {@link com.tms.report.modules.merchantuser.kafka.MerchantApprovalConsumer}
 * existed.
 *
 * <p>
 * The consumer only sees new approvals ({@code auto.offset.reset=latest}), so
 * without this every merchant approved to date stays locked out of the
 * dashboard: no login row means both sign-in and request-activation answer "not
 * found". Deliberately silent — it creates the {@code pending} rows and sends
 * nothing, so a deploy never fans out a burst of activation mail to merchants
 * who were not expecting it. They set their password through the dashboard's
 * own activation / forgot-password flow, both of which only need the row to
 * exist.
 *
 * <p>
 * Idempotent: {@link MerchantProvisioningService#provisionOwner} skips any
 * merchant that already has a login, so re-running on every boot is a no-op
 * once the backlog is cleared.
 */
@Slf4j
@Component
@Order(2)
@Profile("!test")
@RequiredArgsConstructor
public class MerchantLoginBackfillCommand implements CommandLineRunner {

    private final EntityManager entityManager;
    private final MerchantProvisioningService provisioningService;

    @Value("${app.backfill.merchant-logins:true}")
    private boolean enabled;

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }
        try {
            Query query = entityManager.createNativeQuery("""
                    SELECT u.id FROM users u
                    WHERE u.type = 'merchant'
                      AND u.deleted_at IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM merchant.merchant_users m WHERE m.merchant_id = u.id
                      )
                    ORDER BY u.id
                    """);
            List<?> rows = query.getResultList();
            if (rows.isEmpty()) {
                return;
            }

            int created = 0;
            for (Object row : rows) {
                long merchantId = ((Number) row).longValue();
                try {
                    if (provisioningService.provisionOwner(merchantId).isPresent()) {
                        created++;
                    }
                } catch (Exception e) {
                    log.error("Backfill failed for merchant_id={}: {}", merchantId, e.getMessage());
                }
            }
            log.info("Merchant dashboard login backfill: {} of {} merchant(s) without a login were provisioned",
                    created, rows.size());
        } catch (Exception e) {
            // Never block startup on a backfill — the replica may not be ready.
            log.error("Merchant dashboard login backfill skipped: {}", e.getMessage());
        }
    }
}

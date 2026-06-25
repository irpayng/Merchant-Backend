package com.tms.report.core.security;

import com.tms.report.modules.admin.model.Admin;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Per-bank tenant scoping for the super-merchant portal.
 *
 * <p>
 * A bank's universe is its <b>direct merchants</b>: the set of {@code user_id}s
 * that own a TID issued under the bank ({@code tids.bank_code}). Every business
 * read filters by {@code <user_id_col> IN (SELECT user_id FROM tids
 * WHERE bank_code = :smBank)}; TID reads filter on {@code bank_code} directly.
 *
 * <p>
 * Three principal states:
 * <ul>
 * <li><b>Global</b> — the {@code super_admin} role (IRPay staff): no filter,
 * sees every bank.</li>
 * <li><b>Bank-scoped</b> — a non-super user with a {@code bank_code}: filtered
 * to that bank's merchants.</li>
 * <li><b>Unmapped</b> — a non-super user with no {@code bank_code}: fails
 * closed to an empty result set ({@code AND 1=0}). Never "see all".</li>
 * </ul>
 */
@Component
public class TenantScope {

    private static final String BANK_PARAM = "smBank";

    public Admin currentAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminDetails details) {
            return details.getAdmin();
        }
        return null;
    }

    public boolean isGlobal() {
        Admin a = currentAdmin();
        return a != null && a.isSuperAdmin();
    }

    public String bankCode() {
        Admin a = currentAdmin();
        return a != null ? a.getBankCode() : null;
    }

    /**
     * Append the merchant-scope predicate for a query whose user-id column is
     * {@code userIdCol} (e.g. {@code t.user_id}, {@code u.id}). Registers the bound
     * bank param in {@code binds} when bank-scoped.
     */
    public void appendUserScope(StringBuilder sql, Map<String, Object> binds, String userIdCol) {
        if (isGlobal()) {
            return;
        }
        String bank = bankCode();
        if (bank == null || bank.isBlank()) {
            sql.append(" AND 1=0");
            return;
        }
        sql.append(" AND ").append(userIdCol)
                .append(" IN (SELECT sm_scope.user_id FROM tids sm_scope WHERE sm_scope.bank_code = :")
                .append(BANK_PARAM).append(")");
        binds.put(BANK_PARAM, bank);
    }

    /**
     * Append the scope predicate for the {@code tids} table directly, using the
     * given table alias (e.g. {@code td}).
     */
    public void appendTidScope(StringBuilder sql, Map<String, Object> binds, String tidAlias) {
        if (isGlobal()) {
            return;
        }
        String bank = bankCode();
        if (bank == null || bank.isBlank()) {
            sql.append(" AND 1=0");
            return;
        }
        sql.append(" AND ").append(tidAlias).append(".bank_code = :").append(BANK_PARAM);
        binds.put(BANK_PARAM, bank);
    }

    /**
     * Append the scope predicate for an {@code onboardings} query whose id column
     * is {@code onboardingIdCol}: only onboardings that produced one of the bank's
     * direct merchants.
     */
    public void appendOnboardingScope(StringBuilder sql, Map<String, Object> binds, String onboardingIdCol) {
        if (isGlobal()) {
            return;
        }
        String bank = bankCode();
        if (bank == null || bank.isBlank()) {
            sql.append(" AND 1=0");
            return;
        }
        sql.append(" AND ").append(onboardingIdCol)
                .append(" IN (SELECT sm_u.onboarding_id FROM users sm_u WHERE sm_u.onboarding_id IS NOT NULL")
                .append(" AND sm_u.id IN (SELECT sm_scope.user_id FROM tids sm_scope WHERE sm_scope.bank_code = :")
                .append(BANK_PARAM).append("))");
        binds.put(BANK_PARAM, bank);
    }
}

package com.tms.report.core.security;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Per-merchant query scoping — the merchant-dashboard analogue of
 * super-merchant's {@code TenantScope}.
 *
 * <p>
 * Every business read is locked to the authenticated login's merchant
 * ({@code merchant_id} = the merchant's {@code users.id}). A <b>cashier</b> that
 * is bound to a single terminal is additionally narrowed to that terminal, so a
 * till operator only ever sees their own till's activity.
 *
 * <p>
 * Fails closed: if there is no merchant login in context, scoped predicates
 * resolve to an empty result set ({@code AND 1=0}) rather than leaking data.
 */
@Component
public class MerchantScope {

    private static final String MERCHANT_PARAM = "smMerchant";
    private static final String TERMINAL_PARAM = "smTerminal";

    public MerchantUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof MerchantUserDetails details) {
            return details.getMerchantUser();
        }
        return null;
    }

    public Long merchantId() {
        MerchantUser u = current();
        return u != null ? u.getMerchantId() : null;
    }

    /** Terminal id when the login is a cashier locked to one terminal, else null. */
    public Long terminalId() {
        MerchantUser u = current();
        return u != null ? u.getTerminalId() : null;
    }

    public boolean isCashierScoped() {
        return terminalId() != null;
    }

    /**
     * Append the merchant-scope predicate for a query whose merchant user-id
     * column is {@code userIdCol} (e.g. {@code t.user_id}). Registers bound params
     * in {@code binds}.
     */
    public void appendUserScope(StringBuilder sql, Map<String, Object> binds, String userIdCol) {
        Long merchantId = merchantId();
        if (merchantId == null) {
            sql.append(" AND 1=0");
            return;
        }
        sql.append(" AND ").append(userIdCol).append(" = :").append(MERCHANT_PARAM);
        binds.put(MERCHANT_PARAM, merchantId);
    }

    /**
     * Append the merchant scope plus, for a terminal-locked cashier, a device
     * serial narrowing. {@code serialCols} are OR'd (e.g. {@code t.terminal_id},
     * {@code t.metadata->>'serial'}). The bound value is the terminal serial,
     * resolved by the caller from {@link #terminalId()} (the dashboard passes the
     * cashier's terminal serial in as {@code terminalSerial}).
     */
    public void appendTransactionScope(StringBuilder sql, Map<String, Object> binds, String userIdCol,
            String terminalSerial, String... serialCols) {
        appendUserScope(sql, binds, userIdCol);
        if (isCashierScoped() && terminalSerial != null && !terminalSerial.isBlank() && serialCols.length > 0) {
            sql.append(" AND (");
            for (int i = 0; i < serialCols.length; i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append(serialCols[i]).append(" = :").append(TERMINAL_PARAM);
            }
            sql.append(")");
            binds.put(TERMINAL_PARAM, terminalSerial.trim());
        }
    }
}

package com.tms.report.core.security;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security principal for a merchant dashboard login. Authorities are
 * derived from the account's role (no roles/privileges tables): an owner gets
 * the full read surface the modules gate on; a cashier gets a view-only subset.
 * The existing {@code @PreAuthorize} authority names on the read controllers are
 * reused unchanged.
 */
@Getter
public class MerchantUserDetails implements UserDetails {

    /** Full read surface for the business owner. */
    private static final List<String> OWNER_AUTHORITIES = List.of("view_dashboard", "view_transaction",
            "manage_terminal", "access_financial_report", "manage_settlement", "audit");

    /** Cashier: view-only — transactions + terminal status, no financials. */
    private static final List<String> CASHIER_AUTHORITIES = List.of("view_dashboard", "view_transaction",
            "manage_terminal");

    private final MerchantUser merchantUser;

    public MerchantUserDetails(MerchantUser merchantUser) {
        this.merchantUser = merchantUser;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<String> codes = merchantUser.isCashier() ? CASHIER_AUTHORITIES : OWNER_AUTHORITIES;
        return codes.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
    }

    @Override
    public String getPassword() {
        return merchantUser.getPassword();
    }

    @Override
    public String getUsername() {
        return merchantUser.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !merchantUser.isRevoked();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return merchantUser.isActive();
    }
}

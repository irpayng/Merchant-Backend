package com.tms.report.core.security;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.role.model.Privilege;
import com.tms.report.modules.role.model.Role;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security principal for a merchant dashboard login. Authorities are
 * derived from the user's assigned {@link Role} and its {@link Privilege} set.
 * Falls back to hardcoded defaults when no role entity is assigned (backward
 * compat for existing users not yet migrated).
 */
@Getter
public class MerchantUserDetails implements UserDetails {

    /** Fallback: full read surface for the business owner (legacy). */
    private static final List<String> OWNER_AUTHORITIES = List.of("view_dashboard", "view_transaction",
            "manage_terminal", "access_financial_report", "manage_settlement", "audit", "manage_role", "manage_user",
            "manage_notification", "manage_setting", "create_dispute");

    /** Fallback: cashier view-only subset (legacy). */
    private static final List<String> CASHIER_AUTHORITIES = List.of("view_dashboard", "view_transaction",
            "manage_terminal");

    private final MerchantUser merchantUser;

    public MerchantUserDetails(MerchantUser merchantUser) {
        this.merchantUser = merchantUser;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Role role = merchantUser.getRoleEntity();

        // Database-driven: use privileges from the assigned role
        if (role != null && role.getPrivileges() != null && !role.getPrivileges().isEmpty()) {
            Set<Privilege> privileges = role.getPrivileges();
            return privileges.stream().map(p -> new SimpleGrantedAuthority(p.getCode())).collect(Collectors.toSet());
        }

        // Fallback: hardcoded authorities for users not yet assigned a role entity
        List<String> codes = merchantUser.isCashier() ? CASHIER_AUTHORITIES : OWNER_AUTHORITIES;
        return codes.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
    }

    @Override
    public String getPassword() {
        // Password authentication is delegated to tms-user via gRPC
        // This method is not used for login but required by UserDetails interface
        return "";
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

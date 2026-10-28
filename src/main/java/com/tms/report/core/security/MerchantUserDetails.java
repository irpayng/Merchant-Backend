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
 *
 * <p>
 * The {@link #getUsername()} method returns a stable identifier used as the JWT
 * subject. For owners this is the tms-user {@code users.id} (stored as
 * {@code merchantId}); for staff it's the {@code operatorId}. Using a numeric
 * ID rather than email/phone avoids identity mismatches when users change their
 * contact info or onboard via phone-only (no email).
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

    /**
     * Returns a stable, unambiguous identifier for this user. This value becomes
     * the JWT subject and is used for token validation lookups.
     *
     * <p>
     * Format: {@code "u:<merchantId>"} for owners, {@code "o:<operatorId>"} for
     * staff. The prefix disambiguates the ID namespace and makes legacy email/phone
     * subjects easy to detect during the migration window.
     */
    @Override
    public String getUsername() {
        if (merchantUser.isStaff()) {
            // Staff: identified by operatorId (tms-user operators.id)
            return "o:" + merchantUser.getOperatorId();
        }
        // Owner: identified by merchantId (tms-user users.id)
        return "u:" + merchantUser.getMerchantId();
    }

    /**
     * Returns the user's display email, or phone as fallback. Use this for UI
     * display, not for identity/lookup.
     */
    public String getDisplayIdentifier() {
        String email = merchantUser.getEmail();
        if (email != null && !email.isBlank()) {
            return email;
        }
        return merchantUser.getPhoneNumber();
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

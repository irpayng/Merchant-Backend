package com.tms.report.core.command;

import com.tms.report.modules.merchantuser.repository.MerchantUserRepository;
import com.tms.report.modules.role.model.Privilege;
import com.tms.report.modules.role.model.Role;
import com.tms.report.modules.role.repository.PrivilegeRepository;
import com.tms.report.modules.role.repository.RoleRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the system privileges and default roles (owner, cashier) on startup.
 * Idempotent — skips if already present. Runs after AppSeedCommand so the
 * seeded merchant user can be assigned the owner role.
 */
@Slf4j
@Component
@Order(2)
@Profile("!test")
@RequiredArgsConstructor
public class RolePrivilegeSeedCommand implements CommandLineRunner {

    private final PrivilegeRepository privilegeRepository;
    private final RoleRepository roleRepository;
    private final MerchantUserRepository merchantUserRepository;

    @Value("${app.seed.merchant-id:1}")
    private Long seedMerchantId;

    @Value("${app.seed.owner-email:owner@irpay.ng}")
    private String seedOwnerEmail;

    // ── Privilege definitions (code → [name, module]) ───────

    private static final Map<String, String[]> PRIVILEGE_DEFS = new LinkedHashMap<>() {
        {
            put("view_dashboard", new String[]{"View Dashboard", "Dashboard"});
            put("view_transaction", new String[]{"View Transactions", "Transactions"});
            put("export_transaction", new String[]{"Export Transactions", "Transactions"});
            put("manage_terminal", new String[]{"Manage Terminals", "Terminals"});
            put("access_financial_report", new String[]{"Access Financial Reports", "Reports"});
            put("manage_settlement", new String[]{"Manage Settlements", "Reports"});
            put("audit", new String[]{"View Audit Trail", "Activity"});
            put("manage_role", new String[]{"Manage Roles & Privileges", "Administration"});
            put("manage_user", new String[]{"Manage Team Members", "Administration"});
            put("manage_notification", new String[]{"Manage Notifications", "Notifications"});
            put("manage_setting", new String[]{"Manage Settings", "Settings"});
        }
    };

    /** Owner gets all privileges. */
    private static final Set<String> OWNER_PRIVILEGES = PRIVILEGE_DEFS.keySet();

    /** Cashier gets a view-only subset. */
    private static final Set<String> CASHIER_PRIVILEGES = Set.of("view_dashboard", "view_transaction",
            "manage_terminal");

    @Override
    @Transactional
    public void run(String... args) {
        // 1. Seed privileges
        Map<String, Privilege> allPrivileges = seedPrivileges();

        // 2. Seed default roles for the seed merchant
        Role ownerRole = seedRole("owner", "Owner", "Full access to all merchant features", OWNER_PRIVILEGES,
                allPrivileges);
        Role cashierRole = seedRole("cashier", "Cashier", "View-only access to transactions and terminals",
                CASHIER_PRIVILEGES, allPrivileges);

        // 3. Assign the owner role to the seeded dev user (if not already assigned)
        assignSeedUserRole(ownerRole);
    }

    private Map<String, Privilege> seedPrivileges() {
        Map<String, Privilege> map = new java.util.HashMap<>();
        for (var entry : PRIVILEGE_DEFS.entrySet()) {
            String code = entry.getKey();
            String[] meta = entry.getValue();
            Privilege privilege = privilegeRepository.findByCode(code).orElseGet(() -> {
                Privilege p = Privilege.builder().code(code).name(meta[0]).module(meta[1]).build();
                log.info("Seeding privilege: {}", code);
                return privilegeRepository.save(p);
            });
            map.put(code, privilege);
        }
        return map;
    }

    private Role seedRole(String slug, String name, String description, Set<String> privilegeCodes,
            Map<String, Privilege> allPrivileges) {
        return roleRepository.findByMerchantIdAndSlug(seedMerchantId, slug).orElseGet(() -> {
            Set<Privilege> privileges = new HashSet<>();
            for (String code : privilegeCodes) {
                Privilege p = allPrivileges.get(code);
                if (p != null)
                    privileges.add(p);
            }
            Role role = Role.builder().merchantId(seedMerchantId).name(name).slug(slug).description(description)
                    .systemRole(true).privileges(privileges).build();
            log.info("Seeding role: {} (merchant_id={})", slug, seedMerchantId);
            return roleRepository.save(role);
        });
    }

    private void assignSeedUserRole(Role ownerRole) {
        merchantUserRepository.findByEmail(seedOwnerEmail.toLowerCase()).ifPresent(user -> {
            if (user.getRoleEntity() == null) {
                user.setRoleEntity(ownerRole);
                merchantUserRepository.save(user);
                log.info("Assigned owner role to seed user {}", user.getEmail());
            }
        });
    }
}

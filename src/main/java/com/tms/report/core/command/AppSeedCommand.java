package com.tms.report.core.command;

import com.tms.report.modules.admin.model.Admin;
import com.tms.report.modules.admin.repository.AdminRepository;
import com.tms.report.modules.privilege.model.Privilege;
import com.tms.report.modules.privilege.repository.PrivilegeRepository;
import com.tms.report.modules.role.model.Role;
import com.tms.report.modules.role.repository.RoleRepository;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Equivalent of `php artisan app:seed`. Seeds default admin, roles, and
 * privileges. Runs on every startup — all operations are idempotent.
 */
@Slf4j
@Component
@Order(1)
@Profile("!test")
@RequiredArgsConstructor
public class AppSeedCommand implements CommandLineRunner {

    private final AdminRepository adminRepository;
    private final RoleRepository roleRepository;
    private final PrivilegeRepository privilegeRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        log.info("Running app:seed...");

        seedPrivileges();
        seedRoles();
        seedDefaultAdmin();

        log.info("app:seed completed.");
    }

    private void seedPrivileges() {
        List<String[]> defaults = List.of(
                new String[]{"audit", "Audit", "[\"activity\"]",
                        "Allows the user to view all system activities and logs."},
                new String[]{"view_transaction", "View Transaction", "[\"transaction\"]",
                        "Allows the user to view transactions."},
                new String[]{"manage_transaction", "Manage Transaction", "[\"transaction\", \"pos lease\"]",
                        "Allows the user to requery, mark, and waive transactions."},
                new String[]{"manage_kyc", "Manage Kyc", "[\"nin\", \"bvn\", \"cac\", \"address\", \"document\"]",
                        "Allows the user to review and approve KYC (Know Your Customer) information."},
                new String[]{"view_system_configuration", "View System Configuration",
                        "[\"configuration\", \"country\", \"state\", \"bank\", \"lga\", \"status\", \"setting\", \"availability\", \"notification\"]",
                        "Allows the user to view the system's configuration settings."},
                new String[]{"manage_system_configuration", "Manage System Configuration",
                        "[\"configuration\", \"country\", \"state\", \"bank\", \"lga\", \"status\", \"setting\", \"availability\", \"notification\"]",
                        "Allows the user to modify system configuration and settings."},
                new String[]{"manage_dispute", "Manage Dispute", "[\"dispute\", \"conversation\"]",
                        "Allows the user to manage and resolve customer disputes."},
                new String[]{"manage_inventory", "Manage Inventory", "[\"funding alert\", \"provider\"]",
                        "Allows the user to manage product or service inventory."},
                new String[]{"access_financial_report", "Access Financial Report",
                        "[\"statement\", \"cashflow\", \"trial balance\", \"ledger\", \"general ledger\"]",
                        "Allows the user to access and view financial reports."},
                new String[]{"manage_user_profile", "Manage User Profile",
                        "[\"user\", \"client\", \"invitation\", \"tier\", \"upload\"]",
                        "Allows the user to create, update, or delete user profiles."},
                new String[]{"manage_user_wallet", "Manage User Wallet",
                        "[\"wallet\", \"manual funding\", \"payment method\"]",
                        "Allows the user to manage wallet balances, adjustments, and related activities."},
                new String[]{"manage_terminal", "Manage Terminal", "[\"terminal\", \"tid\"]",
                        "Allows the user to manage terminal devices and related configurations."},
                new String[]{"manage_privilege", "Manage Privilege", "[\"privilege\"]",
                        "Allows the user to manage privileges and related configurations."},
                new String[]{"manage_admin", "Manage Admin", "[\"admin\"]",
                        "Allows the user to manage admin accounts."},
                new String[]{"manage_role", "Manage Role", "[\"role\"]", "Allows the user to manage roles."},
                new String[]{"manage_settlement", "Manage Settlement", "[\"settlement\"]",
                        "Allows the user to manage settlements."},
                new String[]{"manage_product", "Manage Product", "[\"product\"]",
                        "Allows the user to manage products."},
                new String[]{"manage_data_plan", "Manage Data Plan", "[\"data plan\"]",
                        "Allows the user to manage data plans."},
                new String[]{"view_dashboard", "View Dashboard", "[\"dashboard\"]",
                        "Allows the user to view the dashboard."},
                new String[]{"manage_manual_funding", "Manage Manual Funding", "[\"manual funding\"]",
                        "Allows the user to manage manual funding requests."},
                new String[]{"manage_stock_purchase", "Manage Stock Purchase", "[\"stock purchase\"]",
                        "Allows the user to manage stock purchases."},
                new String[]{"view_iso_request", "View Iso Request", "[\"iso request\"]",
                        "Allows the user to view ISO requests."},
                new String[]{"view_http_request", "View Http Request", "[\"http request\"]",
                        "Allows the user to view HTTP requests."},
                new String[]{"view_fraud_monitoring", "View Fraud Monitoring",
                        "[\"balance incident\", \"anomaly\", \"overspent user\", \"rapid fire\"]",
                        "Allows the user to view fraud monitoring data including balance incidents, anomaly detection, and overspent users."},
                new String[]{"manage_system_reset", "Manage System Reset", "[\"system reset\"]",
                        "Allows the user to wipe and re-seed the staging databases (staging only)."});

        for (String[] p : defaults) {
            if (privilegeRepository.findByCode(p[0]).isEmpty()) {
                privilegeRepository.save(Privilege.builder().code(p[0]).name(p[1]).modulesRaw(p[2]).description(p[3])
                        .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
                log.info("  Created privilege: {}", p[0]);
            }
        }
    }

    private void seedRoles() {
        // Role definitions: code, name, privilege codes (null = no default privileges,
        // "*" = all)
        record RoleDef(String code, String name, List<String> privileges) {
        }

        List<RoleDef> defaults = List.of(new RoleDef("super_admin", "Super Admin", List.of("*")),
                new RoleDef("bank_admin", "Bank Admin",
                        List.of("view_dashboard", "view_transaction", "manage_terminal", "manage_user_profile",
                                "manage_kyc")),
                new RoleDef("bank_operator", "Bank Operator",
                        List.of("view_dashboard", "view_transaction", "manage_terminal")));

        for (RoleDef r : defaults) {
            Role role = roleRepository.findByCode(r.code()).orElse(null);
            if (role == null) {
                role = roleRepository.save(Role.builder().name(r.name()).code(r.code()).createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now()).build());
                log.info("  Created role: {}", r.code());
            }

            // Assign default privileges
            if (r.privileges().contains("*")) {
                // Super admin gets all privileges
                Set<Privilege> allPrivileges = new HashSet<>(privilegeRepository.findAll());
                if (!allPrivileges.equals(role.getPrivileges())) {
                    role.setPrivileges(allPrivileges);
                    roleRepository.save(role);
                    log.info("  Synced all privileges to {} role", r.code());
                }
            } else if (!r.privileges().isEmpty() && role.getPrivileges().isEmpty()) {
                // Only seed default privileges if the role has no privileges yet
                Set<Privilege> defaultPrivileges = new HashSet<>();
                for (String code : r.privileges()) {
                    privilegeRepository.findByCode(code).ifPresent(defaultPrivileges::add);
                }
                if (!defaultPrivileges.isEmpty()) {
                    role.setPrivileges(defaultPrivileges);
                    roleRepository.save(role);
                    log.info("  Assigned default privileges to {} role", r.code());
                }
            }
        }
    }

    private void seedDefaultAdmin() {
        String email = "admin@irpay.ng";
        if (adminRepository.findByEmail(email).isEmpty()) {
            Role superAdmin = roleRepository.findByCode("super_admin").orElseThrow();
            adminRepository.save(Admin.builder().name("Admin").email(email).password(passwordEncoder.encode("milimatr"))
                    .roles(Set.of(superAdmin)).emailVerifiedAt(LocalDateTime.now()).createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now()).build());
            log.info("  Created default admin: {}", email);
        }
    }
}

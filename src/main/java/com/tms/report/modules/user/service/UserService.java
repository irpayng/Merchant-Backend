package com.tms.report.modules.user.service;

import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.core.security.TenantScope;
import com.tms.report.core.util.Avatars;
import com.tms.report.modules.user.dto.TierDto;
import com.tms.report.modules.user.dto.UserDto;
import com.tms.report.modules.user.model.User;
import com.tms.report.modules.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final TenantScope tenantScope;

    /**
     * Throws {@link EntityNotFoundException} if the given user is not one of the
     * caller's bank's direct merchants. No-op for global users.
     */
    private void assertUserInScope(Long userId) {
        if (tenantScope.isGlobal()) {
            return;
        }
        String bank = tenantScope.bankCode();
        boolean ok = bank != null && !bank.isBlank() && userId != null
                && ((Number) entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM tids WHERE bank_code = :bank AND user_id = :uid")
                        .setParameter("bank", bank).setParameter("uid", userId).getSingleResult()).longValue() > 0;
        if (!ok) {
            throw new EntityNotFoundException("User not found");
        }
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<UserDto> index(Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        StringBuilder where = new StringBuilder("WHERE 1=1");
        Map<String, Object> qp = new HashMap<>();

        String search = params.get("search");
        if (search != null && !search.isBlank()) {
            String[] words = search.toLowerCase().trim().split("\\s+");
            String fullName = "LOWER(COALESCE(p.first_name, '') || ' ' || COALESCE(p.middle_name, '') || ' ' || COALESCE(p.last_name, ''))";
            where.append(" AND (LOWER(u.email) LIKE :search OR LOWER(u.phone_number) LIKE :search OR (");
            for (int i = 0; i < words.length; i++) {
                if (i > 0)
                    where.append(" AND ");
                String param = "nameWord" + i;
                where.append(fullName).append(" LIKE :").append(param);
                qp.put(param, "%" + words[i] + "%");
            }
            where.append("))");
            qp.put("search", "%" + search.toLowerCase() + "%");
        }

        String type = params.get("type");
        if (type != null && !type.isBlank()) {
            // Accept a comma-separated list so callers can ask for an
            // "aggregator-class" set (e.g. type=aggregator,super_aggregator)
            // without losing the single-value exact match the Users table
            // filter relies on to keep "Aggregator" and "Super Aggregator"
            // as distinct chips.
            List<String> types = Arrays.stream(type.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
            if (types.size() == 1) {
                where.append(" AND u.type = :type");
                qp.put("type", types.get(0));
            } else if (!types.isEmpty()) {
                where.append(" AND u.type IN (:types)");
                qp.put("types", types);
            }
        }

        String tierId = params.get("tier_id");
        if (tierId != null && !tierId.isBlank()) {
            where.append(" AND u.tier_id = :tierId");
            qp.put("tierId", Long.parseLong(tierId));
        }

        String pndFilter = params.get("pnd");
        if ("yes".equalsIgnoreCase(pndFilter)) {
            where.append(" AND EXISTS (SELECT 1 FROM wallets w WHERE w.user_id = u.id AND w.pnd = true)");
        } else if ("no".equalsIgnoreCase(pndFilter)) {
            where.append(" AND NOT EXISTS (SELECT 1 FROM wallets w WHERE w.user_id = u.id AND w.pnd = true)");
        }

        QueryFilterHelper.applyDates(where, qp, params, "u.created_at");
        // Per-bank tenant scope: only this bank's direct merchants.
        tenantScope.appendUserScope(where, qp, "u.id");

        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);

        String sql = """
                SELECT u.id, TRIM(COALESCE(p.first_name, '') || ' ' || COALESCE(p.last_name, '')) as name,
                       u.email, u.phone_number, u.type,
                       t.name as tier_name, t.code as tier_code,
                       (SELECT MAX(tr.created_at) FROM transactions tr WHERE tr.user_id = u.id) as last_transaction_date,
                       CASE WHEN EXISTS (SELECT 1 FROM transactions tr WHERE tr.user_id = u.id AND tr.created_at >= :sevenDaysAgo) THEN true ELSE false END as active,
                       CASE WHEN EXISTS (SELECT 1 FROM wallets w WHERE w.user_id = u.id AND w.pnd = true) THEN true ELSE false END as pnd,
                       u.created_at,
                       u.parent_id,
                       pu.email as parent_email,
                       TRIM(COALESCE(pp.first_name, '') || ' ' || COALESCE(pp.last_name, '')) as parent_name,
                       u.bvn_photo_url,
                       (SELECT w.pnd_reason FROM wallets w WHERE w.user_id = u.id AND w.pnd = true AND w.pnd_reason IS NOT NULL LIMIT 1) as pnd_reason,
                       u.frozen_at, u.suspended_at, u.blocked_at
                FROM users u
                LEFT JOIN profiles p ON p.user_id = u.id
                LEFT JOIN tiers t ON t.code = CAST(u.tier_id AS text)
                LEFT JOIN users pu ON pu.id = u.parent_id
                LEFT JOIN profiles pp ON pp.user_id = pu.id
                """
                + where + " ORDER BY u.created_at DESC";

        String countSql = "SELECT COUNT(*) FROM users u LEFT JOIN profiles p ON p.user_id = u.id "
                + "LEFT JOIN users pu ON pu.id = u.parent_id LEFT JOIN profiles pp ON pp.user_id = pu.id " + where;

        Query countQuery = entityManager.createNativeQuery(countSql);
        Query dataQuery = entityManager.createNativeQuery(sql);

        qp.forEach(countQuery::setParameter);
        qp.forEach(dataQuery::setParameter);
        dataQuery.setParameter("sevenDaysAgo", sevenDaysAgo);

        long total = ((Number) countQuery.getSingleResult()).longValue();

        dataQuery.setFirstResult(page * limit);
        dataQuery.setMaxResults(limit);

        List<Object[]> rows = dataQuery.getResultList();
        List<UserDto> dtos = rows.stream().map(this::mapRowToUserDto).toList();

        return new PageImpl<>(dtos, PageRequest.of(page, limit), total);
    }

    public UserDto show(Long id) {
        assertUserInScope(id);
        User user = userRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("User not found"));
        return toDto(user);
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> showDetail(Long id) {
        assertUserInScope(id);
        // Main user query with profile, tier
        Query q = entityManager.createNativeQuery("SELECT u.id, u.email, u.phone_number, u.type, u.account_number, "
                + "u.parent_id, u.created_at, u.onboarding_id, "
                + "p.first_name, p.middle_name, p.last_name, p.date_of_birth, p.gender, "
                + "t.id as tier_id, t.name as tier_name, t.code as tier_code, u.frozen_at, u.bvn_photo_url, "
                + "u.suspended_at, u.suspended_reason, u.suspended_by_type, u.blocked_at, u.blocked_reason "
                + "FROM users u LEFT JOIN profiles p ON p.user_id = u.id "
                + "LEFT JOIN tiers t ON t.code = CAST(u.tier_id AS text) WHERE u.id = :id");
        q.setParameter("id", id);
        Object[] r = (Object[]) q.getSingleResult();
        // Columns: 0=id, 1=email, 2=phone, 3=type, 4=account_number,
        // 5=parent_id, 6=created_at, 7=onboarding_id,
        // 8=first_name, 9=middle_name, 10=last_name, 11=dob, 12=gender,
        // 13=tier_id, 14=tier_name, 15=tier_code, 16=frozen_at, 17=bvn_photo_url,
        // 18=suspended_at, 19=suspended_reason, 20=suspended_by_type
        // 21=blocked_at, 22=blocked_reason

        String firstName = str(r[8]), lastName = str(r[10]);
        String name = (firstName != null || lastName != null)
                ? ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim()
                : str(r[1]);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", lng(r[0]));
        data.put("name", name.isBlank() ? str(r[1]) : name);
        data.put("email", str(r[1]));
        data.put("phone_number", str(r[2]));
        data.put("account_number", str(r[4]));
        data.put("avatar", Avatars.avatar(r.length > 17 && r[17] != null ? str(r[17]) : null));
        data.put("tier", r[13] != null ? Map.of("id", lng(r[13]), "name", str(r[14]), "code", str(r[15])) : null);
        data.put("type", str(r[3]));

        // created_at
        if (r[6] != null) {
            LocalDateTime cat = toLocalDateTime(r[6]);
            String catStr = cat.toString().replace("T", " ");
            data.put("created_at", catStr.substring(0, Math.min(16, catStr.length())));
        } else {
            data.put("created_at", null);
        }

        // Active check
        boolean active = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM transactions WHERE user_id = :id AND created_at >= :since")
                .setParameter("id", id).setParameter("since", LocalDateTime.now().minusDays(7)).getSingleResult())
                .longValue() > 0;
        data.put("last_transaction_date", null);
        Query ltq = entityManager.createNativeQuery("SELECT MAX(created_at) FROM transactions WHERE user_id = :id");
        ltq.setParameter("id", id);
        Object ltd = ltq.getSingleResult();
        if (ltd != null) {
            String ltdStr = ltd.toString();
            data.put("last_transaction_date", ltdStr.length() > 16 ? ltdStr.substring(0, 16) : ltdStr);
        }
        data.put("active", active);
        data.put("suspended", r.length > 18 && r[18] != null);
        data.put("suspended_reason", r.length > 19 ? str(r[19]) : null);
        data.put("suspended_by_type", r.length > 20 ? str(r[20]) : null);
        data.put("blocked", r.length > 21 && r[21] != null);
        data.put("blocked_reason", r.length > 22 ? str(r[22]) : null);

        // Frozen-account flag — surfaces lockouts from the 3-strike PIN policy
        // (and admin-initiated freezes) so the dashboard can render the right
        // banner and unfreeze action.
        data.put("frozen", r[16] != null);
        if (r[16] != null) {
            String frozenAtStr = r[16].toString();
            data.put("frozen_at", frozenAtStr.length() > 19 ? frozenAtStr.substring(0, 19) : frozenAtStr);
        } else {
            data.put("frozen_at", null);
        }

        // PND — check wallets table (pnd column is the source of truth)
        boolean pnd = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM wallets WHERE user_id = :id AND pnd = true")
                .setParameter("id", id).getSingleResult()).longValue() > 0;
        data.put("pnd", pnd);

        // PND reason — surfaces the wallet's pnd_reason text (set by the wallet
        // service when an admin or the system applies a PND) so the UI can show
        // it as a tooltip on the PND badge.
        String pndReason = null;
        if (pnd) {
            try {
                Object reason = entityManager.createNativeQuery(
                        "SELECT pnd_reason FROM wallets WHERE user_id = :id AND pnd = true AND pnd_reason IS NOT NULL LIMIT 1")
                        .setParameter("id", id).getSingleResult();
                pndReason = reason != null ? reason.toString() : null;
            } catch (Exception e) {
                pndReason = null;
            }
        }
        data.put("pnd_reason", pndReason);

        // KYC
        Map<String, Object> kyc = new LinkedHashMap<>();
        kyc.put("bvn", loadBvn(id));
        kyc.put("nin", loadNin(id));
        kyc.put("address", loadAddress(id));
        data.put("kyc", kyc);

        // Location (from address)
        data.put("location", loadLocation(id));
        data.put("number_of_terminals", 0);

        // Commissions
        Map<String, Object> commissions = new LinkedHashMap<>();
        commissions.put("day",
                getCommission(id, LocalDateTime.now().toLocalDate().atStartOfDay(), LocalDateTime.now()));
        commissions.put("week", getCommission(id, LocalDateTime.now().minusDays(7), LocalDateTime.now()));
        commissions.put("month", getCommission(id, LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay(),
                LocalDateTime.now()));
        data.put("total_commissions", commissions);

        // Virtual accounts
        List<Object[]> vaRows = entityManager.createNativeQuery(
                "SELECT account_name, account_number, bank_name, bank_code FROM virtual_accounts WHERE user_id = :id")
                .setParameter("id", id).getResultList();
        data.put("virtual_accounts", vaRows.stream().map(va -> Map.of("account_name", str(va[0]), "account_number",
                str(va[1]), "bank_name", str(va[2]), "bank_code", str(va[3]))).toList());

        // Wallets
        List<Object[]> wRows = entityManager
                .createNativeQuery("SELECT id, type, balance FROM wallets WHERE user_id = :id").setParameter("id", id)
                .getResultList();
        data.put("wallets",
                wRows.stream().map(w -> Map.of("id", lng(w[0]), "type", str(w[1]), "balance", str(w[2]))).toList());

        // Profile
        if (r[8] != null || r[10] != null) {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("first_name", str(r[8]));
            profile.put("middle_name", str(r[9]));
            profile.put("last_name", str(r[10]));
            profile.put("date_of_birth", str(r[11]));
            profile.put("gender", str(r[12]));
            data.put("profile", profile);
        } else {
            data.put("profile", null);
        }

        // Transaction stats
        Object[] txStats = (Object[]) entityManager
                .createNativeQuery("SELECT COUNT(*), COALESCE(SUM(amount),0), "
                        + "COALESCE(SUM(CASE WHEN status_code='completed' THEN 1 ELSE 0 END),0), "
                        + "COALESCE(SUM(CASE WHEN status_code='failed' THEN 1 ELSE 0 END),0), "
                        + "COALESCE(SUM(CASE WHEN created_at >= :monthStart THEN 1 ELSE 0 END),0), "
                        + "COALESCE(SUM(CASE WHEN created_at >= :monthStart THEN amount ELSE 0 END),0) "
                        + "FROM transactions WHERE user_id = :id")
                .setParameter("id", id)
                .setParameter("monthStart", LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay())
                .getSingleResult();
        data.put("transaction_stats",
                Map.of("total_count", ((Number) txStats[0]).intValue(), "total_volume",
                        ((Number) txStats[1]).doubleValue(), "successful_count", ((Number) txStats[2]).intValue(),
                        "failed_count", ((Number) txStats[3]).intValue(), "this_month_count",
                        ((Number) txStats[4]).intValue(), "this_month_volume", ((Number) txStats[5]).doubleValue()));

        data.put("onboarded_at", null);
        data.put("app_version", null);
        data.put("platform", null);

        // Parent
        if (r[5] != null) {
            try {
                Object[] parent = (Object[]) entityManager
                        .createNativeQuery(
                                "SELECT u.email, COALESCE(CONCAT(p.first_name,' ',p.last_name), u.email) as name "
                                        + "FROM users u LEFT JOIN profiles p ON p.user_id = u.id WHERE u.id = :pid")
                        .setParameter("pid", lng(r[5])).getSingleResult();
                Long parentId = lng(r[5]);
                data.put("parent", Map.of("id", parentId, "name", str(parent[1]), "email", str(parent[0])));
            } catch (Exception e) {
                data.put("parent", null);
            }
        } else {
            data.put("parent", null);
        }

        data.put("cac", null);
        data.put("invited_users_count",
                ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM users WHERE parent_id = :id")
                        .setParameter("id", id).getSingleResult()).intValue());

        // Transaction locations for this user
        data.put("transaction_locations", loadTransactionLocations(id));

        return data;
    }

    private Map<String, Object> loadBvn(Long userId) {
        try {
            // BVN data is now on the users table (users.bvn field)
            // No separate bvns table in microservice schema
            Object[] r = (Object[]) entityManager
                    .createNativeQuery("SELECT u.bvn, p.first_name, p.middle_name, p.last_name, u.phone_number, "
                            + "u.email, p.gender, p.date_of_birth "
                            + "FROM users u LEFT JOIN profiles p ON p.user_id = u.id WHERE u.id = :id AND u.bvn IS NOT NULL")
                    .setParameter("id", userId).getSingleResult();
            if (r[0] == null)
                return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("number", str(r[0]));
            m.put("first_name", str(r[1]));
            m.put("middle_name", str(r[2]));
            m.put("last_name", str(r[3]));
            m.put("phone_number", str(r[4]));
            m.put("email", str(r[5]));
            m.put("gender", str(r[6]));
            m.put("date_of_birth", str(r[7]));
            m.put("status_code", "completed");
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> loadNin(Long userId) {
        try {
            // NIN table exists in KYC service, no statuses JOIN needed
            Object[] r = (Object[]) entityManager
                    .createNativeQuery("SELECT n.number, n.first_name, n.middle_name, n.last_name, n.phone_number, "
                            + "n.email, n.gender, n.date_of_birth, n.status_code "
                            + "FROM nins n WHERE n.user_id = :id")
                    .setParameter("id", userId).getSingleResult();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("number", str(r[0]));
            m.put("first_name", str(r[1]));
            m.put("middle_name", str(r[2]));
            m.put("last_name", str(r[3]));
            m.put("phone_number", str(r[4]));
            m.put("email", str(r[5]));
            m.put("gender", str(r[6]));
            m.put("date_of_birth", str(r[7]));
            m.put("status_code", str(r[8]));
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> loadAddress(Long userId) {
        try {
            Object[] r = (Object[]) entityManager
                    .createNativeQuery("SELECT a.address, a.lga, s.name as state, c.name as country "
                            + "FROM addresses a LEFT JOIN states s ON s.id = a.state_id "
                            + "LEFT JOIN countries c ON c.id = a.country_id "
                            + "WHERE a.addressable_type = 'users' AND a.addressable_id = :id "
                            + "ORDER BY a.created_at DESC LIMIT 1")
                    .setParameter("id", userId).getSingleResult();
            return Map.of("address", str(r[0]), "lga", str(r[1]), "state", str(r[2]), "country", str(r[3]),
                    "status_code", "completed");
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> loadLocation(Long userId) {
        try {
            Object[] r = (Object[]) entityManager
                    .createNativeQuery("SELECT a.address, a.lga, s.name as state, c.name as country "
                            + "FROM addresses a LEFT JOIN states s ON s.id = a.state_id "
                            + "LEFT JOIN countries c ON c.id = a.country_id "
                            + "WHERE a.addressable_type = 'users' AND a.addressable_id = :id "
                            + "ORDER BY a.created_at DESC LIMIT 1")
                    .setParameter("id", userId).getSingleResult();
            return Map.of("address", str(r[0]), "lga", str(r[1]), "state", str(r[2]), "country", str(r[3]));
        } catch (Exception e) {
            return null;
        }
    }

    private double getCommission(Long userId, LocalDateTime start, LocalDateTime end) {
        try {
            return ((Number) entityManager
                    .createNativeQuery("SELECT COALESCE(SUM(s.amount),0) FROM statements s "
                            + "JOIN wallets w ON w.id = s.wallet_id " + "WHERE w.user_id = :id "
                            + "AND w.type = 'commission' AND s.type = 'credit' "
                            + "AND s.created_at BETWEEN :start AND :end")
                    .setParameter("id", userId).setParameter("start", start).setParameter("end", end).getSingleResult())
                    .doubleValue();
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadTransactionLocations(Long userId) {
        try {
            List<Object[]> rows = entityManager
                    .createNativeQuery("SELECT t.latitude, t.longitude, t.reference, t.amount, t.status_code, "
                            + "t.created_at, pr.name as product_name " + "FROM transactions t "
                            + "LEFT JOIN products pr ON pr.id = t.product_id "
                            + "WHERE t.user_id = :id AND t.latitude IS NOT NULL AND t.longitude IS NOT NULL "
                            + "ORDER BY t.created_at DESC LIMIT 50")
                    .setParameter("id", userId).getResultList();

            List<Map<String, Object>> locations = new java.util.ArrayList<>();
            for (Object[] row : rows) {
                Map<String, Object> loc = new LinkedHashMap<>();
                loc.put("latitude", ((Number) row[0]).doubleValue());
                loc.put("longitude", ((Number) row[1]).doubleValue());
                loc.put("reference", str(row[2]));
                loc.put("amount", row[3] != null ? ((Number) row[3]).doubleValue() : 0);
                loc.put("status", str(row[4]));
                loc.put("created_at", row[5] != null ? row[5].toString() : null);
                loc.put("product", str(row[6]));
                locations.add(loc);
            }
            return locations;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Long lng(Object o) {
        return o != null ? ((Number) o).longValue() : null;
    }
    private String str(Object o) {
        return o != null ? o.toString().trim() : null;
    }

    private LocalDateTime toLocalDateTime(Object o) {
        if (o == null)
            return null;
        if (o instanceof Timestamp ts)
            return ts.toLocalDateTime();
        if (o instanceof LocalDateTime ldt)
            return ldt;
        if (o instanceof Instant inst)
            return LocalDateTime.ofInstant(inst, ZoneId.systemDefault());
        if (o instanceof java.time.OffsetDateTime odt)
            return odt.toLocalDateTime();
        try {
            return LocalDateTime
                    .parse(o.toString().replace(" ", "T").substring(0, Math.min(19, o.toString().length())));
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> getSummary() {
        long total = userRepository.countTotal();
        long active = userRepository.countActive();
        Map<String, Object> summary = new HashMap<>();
        summary.put("total", total);
        summary.put("active", active);
        summary.put("inactive", total - active);
        return summary;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> agentStats() {
        // Simplified: count agents, no address verification status (different table in
        // microservices)
        Query q = entityManager.createNativeQuery("""
                SELECT COUNT(DISTINCT u.id) as total_agents,
                       0 as pending,
                       0 as verified
                FROM users u
                WHERE u.type = 'agent'
                """);
        Object[] row = (Object[]) q.getSingleResult();
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_agents", ((Number) row[0]).intValue());
        stats.put("pending_verification_agents", ((Number) row[1]).intValue());
        stats.put("verified_agents", ((Number) row[2]).intValue());
        return stats;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getTopPerformingAgents(Map<String, String> params) {
        LocalDateTime start = params.containsKey("start_date")
                ? LocalDateTime.parse(params.get("start_date"))
                : LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay();
        LocalDateTime end = params.containsKey("end_date")
                ? LocalDateTime.parse(params.get("end_date"))
                : LocalDateTime.now();

        Query q = entityManager.createNativeQuery("""
                SELECT u.id, u.email,
                       COALESCE(p.first_name, '') || ' ' || COALESCE(p.last_name, '') as name,
                       COALESCE(SUM(t.amount), 0) as performance,
                       COUNT(t.id) as transaction_count
                FROM users u
                JOIN profiles p ON p.user_id = u.id
                LEFT JOIN transactions t ON t.user_id = u.id
                    AND t.status_code = 'completed'
                    AND t.created_at BETWEEN :start AND :end
                WHERE u.type = 'agent'
                GROUP BY u.id, u.email, p.first_name, p.last_name
                HAVING COALESCE(SUM(t.amount), 0) > 0
                ORDER BY COALESCE(SUM(t.amount), 0) DESC
                LIMIT 4
                """);
        q.setParameter("start", start);
        q.setParameter("end", end);

        List<Object[]> rows = q.getResultList();
        return rows.stream().map(row -> {
            Map<String, Object> agent = new HashMap<>();
            String name = row[2] != null ? row[2].toString().trim() : row[1].toString();
            agent.put("name", name.isBlank() ? row[1].toString() : name);
            agent.put("email", row[1]);
            agent.put("performance", ((Number) row[3]).doubleValue());
            agent.put("transaction_count", ((Number) row[4]).intValue());
            return agent;
        }).toList();
    }

    public Map<String, Object> getFilters() {
        Query q = entityManager.createNativeQuery("SELECT id, name FROM tiers ORDER BY name");
        @SuppressWarnings("unchecked")
        List<Object[]> tiers = q.getResultList();

        Map<String, Object> filters = new HashMap<>();
        filters.put("tiers", tiers.stream().map(r -> Map.of("id", r[0].toString(), "name", r[1].toString())).toList());
        filters.put("pnd", List.of(Map.of("id", "yes", "name", "Yes"), Map.of("id", "no", "name", "No")));
        filters.put("types",
                List.of(Map.of("id", "user", "name", "User"), Map.of("id", "agent", "name", "Agent"),
                        Map.of("id", "aggregator", "name", "Aggregator"),
                        Map.of("id", "super_aggregator", "name", "Super Aggregator"),
                        Map.of("id", "merchant", "name", "Merchant")));
        filters.put("aggregators", listAggregators(null));
        return filters;
    }

    private UserDto mapRowToUserDto(Object[] row) {
        String name = row[1] != null ? row[1].toString().trim() : null;
        if (name != null && name.isBlank())
            name = row[2] != null ? row[2].toString() : null;

        TierDto tierDto = null;
        if (row[5] != null) {
            tierDto = TierDto.builder().name(row[5].toString()).code(row[6] != null ? row[6].toString() : null).build();
        }

        LocalDateTime lastTxDate = toLocalDateTime(row[7]);

        LocalDateTime createdAt = toLocalDateTime(row[10]);

        // Parent info: row[11]=parent_id, row[12]=parent_email, row[13]=parent_name
        Object parent = null;
        if (row[11] != null) {
            String parentName = row[13] != null ? row[13].toString().trim() : null;
            String parentEmail = row[12] != null ? row[12].toString() : null;
            parent = Map.of("id", lng(row[11]), "email", parentEmail != null ? parentEmail : "", "name",
                    (parentName != null && !parentName.isBlank())
                            ? parentName
                            : (parentEmail != null ? parentEmail : ""));
        }

        // Avatar: row[14]=bvn_photo_url. The column stores the bare S3 key
        // (e.g. "images/bvn/original/01KFP3RQ...jpeg"); presign for the UI.
        Object avatar = Avatars.avatar(row.length > 14 && row[14] != null ? str(row[14]) : null);

        // pnd_reason: row[15] — populated from wallets.pnd_reason when the user
        // has an active PND. Surfaced in the UI as a tooltip on the PND badge.
        String pndReason = row.length > 15 && row[15] != null ? str(row[15]) : null;

        // Account-state flags: row[16]=frozen_at, row[17]=suspended_at,
        // row[18]=blocked_at. Surfaced as booleans so the Users table can show a
        // Status column and the row-action menu can toggle the right item
        // (Block↔Unblock, Suspend↔Reinstate, Unfreeze).
        boolean frozen = row.length > 16 && row[16] != null;
        boolean suspended = row.length > 17 && row[17] != null;
        boolean blocked = row.length > 18 && row[18] != null;

        return UserDto.builder().id(((Number) row[0]).longValue()).name(name)
                .email(row[2] != null ? row[2].toString() : null).avatar(avatar)
                .phoneNumber(row[3] != null ? row[3].toString() : null).tier(tierDto)
                .type(row[4] != null ? row[4].toString() : null).lastTransactionDate(lastTxDate)
                .active((Boolean) row[8]).pnd((Boolean) row[9]).pndReason(pndReason).frozen(frozen).suspended(suspended)
                .blocked(blocked).parent(parent).createdAt(createdAt).build();
    }

    private UserDto toDto(User user) {
        return UserDto.builder().id(user.getId()).name(user.getName()).email(user.getEmail())
                .avatar(Avatars.avatar(user.getBvnPhotoUrl())).phoneNumber(user.getPhoneNumber()).type(user.getType())
                .active(user.getIsActive()).pnd(user.getPnd()).lastTransactionDate(user.getLastTransactionDate())
                .createdAt(user.getCreatedAt()).build();
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<Map<String, Object>> getUserStatements(Long userId, Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        StringBuilder where = new StringBuilder("WHERE w.user_id = :userId");
        Map<String, Object> qp = new HashMap<>();
        qp.put("userId", userId);

        // Filters matching PHP Statement model
        String type = params.get("type");
        if (type != null && !type.isBlank()) {
            where.append(" AND s.type = :ftype");
            qp.put("ftype", type);
        }
        String walletType = params.get("wallet_type");
        if (walletType != null && !walletType.isBlank()) {
            where.append(" AND w.type = :fwalletType");
            qp.put("fwalletType", walletType);
        }
        QueryFilterHelper.applyDates(where, qp, params, "s.created_at");

        String baseSql = "FROM statements s JOIN wallets w ON w.id = s.wallet_id " + where;
        String sql = "SELECT s.id, s.amount, s.type, s.description, s.previous_balance, s.current_balance, "
                + "w.type as wallet_type, s.created_at, s.source_reference " + baseSql + " ORDER BY s.created_at DESC";
        String countSql = "SELECT COUNT(*) " + baseSql;

        Query countQ = entityManager.createNativeQuery(countSql);
        qp.forEach(countQ::setParameter);
        long total = ((Number) countQ.getSingleResult()).longValue();

        Query q = entityManager.createNativeQuery(sql);
        qp.forEach(q::setParameter);
        q.setFirstResult(page * limit);
        q.setMaxResults(limit);

        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> dtos = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) r[0]).longValue());
            m.put("reference", str(r[8]));
            m.put("amount", r[1] != null ? ((Number) r[1]).doubleValue() : 0);
            m.put("type", str(r[2]));
            m.put("description", str(r[3]));
            m.put("previous_balance", r[4] != null ? ((Number) r[4]).doubleValue() : 0);
            m.put("current_balance", r[5] != null ? ((Number) r[5]).doubleValue() : 0);
            m.put("wallet_type", str(r[6]));
            m.put("user_name", null);
            m.put("user_email", null);
            m.put("created_at", r[7] != null ? r[7].toString() : null);
            return m;
        }).toList();

        return new PageImpl<>(dtos, PageRequest.of(page, limit), total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUserStatementStats(Long userId, Map<String, String> params) {
        StringBuilder where = new StringBuilder("WHERE w.user_id = :userId");
        Map<String, Object> qp = new HashMap<>();
        qp.put("userId", userId);

        String type = params.get("type");
        if (type != null && !type.isBlank()) {
            where.append(" AND s.type = :ftype");
            qp.put("ftype", type);
        }
        QueryFilterHelper.applyDates(where, qp, params, "s.created_at");

        String sql = "SELECT COALESCE(SUM(CASE WHEN s.type = 'credit' THEN s.amount ELSE 0 END), 0) as inflow, "
                + "COALESCE(SUM(CASE WHEN s.type = 'debit' THEN s.amount ELSE 0 END), 0) as outflow "
                + "FROM statements s JOIN wallets w ON w.id = s.wallet_id " + where;
        Query q = entityManager.createNativeQuery(sql);
        qp.forEach(q::setParameter);
        Object[] r = (Object[]) q.getSingleResult();
        return Map.of("inflow", ((Number) r[0]).doubleValue(), "outflow", ((Number) r[1]).doubleValue());
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<Map<String, Object>> getUserWallets(Long userId, Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        StringBuilder where = new StringBuilder("WHERE w.user_id = :userId");
        Map<String, Object> qp = new HashMap<>();
        qp.put("userId", userId);

        String type = params.get("type");
        if (type != null && !type.isBlank()) {
            where.append(" AND w.type = :wtype");
            qp.put("wtype", type);
        }

        String baseSql = "FROM wallets w " + where;
        String sql = "SELECT w.id, w.type, w.balance, "
                + "COALESCE((SELECT SUM(s.amount) FROM statements s WHERE s.wallet_id = w.id AND s.type = 'credit'), 0) as inflow, "
                + "COALESCE((SELECT SUM(s.amount) FROM statements s WHERE s.wallet_id = w.id AND s.type = 'debit'), 0) as outflow, "
                + "w.created_at " + baseSql + " ORDER BY w.created_at DESC";
        String countSql = "SELECT COUNT(*) " + baseSql;

        Query countQ = entityManager.createNativeQuery(countSql);
        qp.forEach(countQ::setParameter);
        long total = ((Number) countQ.getSingleResult()).longValue();

        Query q = entityManager.createNativeQuery(sql);
        qp.forEach(q::setParameter);
        q.setFirstResult(page * limit);
        q.setMaxResults(limit);

        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> dtos = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) r[0]).longValue());
            m.put("type", str(r[1]));
            m.put("balance", r[2] != null ? r[2].toString() : "0");
            m.put("inflow", r[3] != null ? r[3].toString() : "0");
            m.put("outflow", r[4] != null ? r[4].toString() : "0");
            m.put("created_at", r[5] != null ? r[5].toString() : null);
            return m;
        }).toList();

        return new PageImpl<>(dtos, PageRequest.of(page, limit), total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUserWalletStats(Long userId, Map<String, String> params) {
        StringBuilder where = new StringBuilder("WHERE w.user_id = :userId");
        Map<String, Object> qp = new HashMap<>();
        qp.put("userId", userId);

        String type = params.get("type");
        if (type != null && !type.isBlank()) {
            where.append(" AND w.type = :wtype");
            qp.put("wtype", type);
        }

        String sql = "SELECT COALESCE(SUM(w.balance), 0) as closing_balance, "
                + "COALESCE((SELECT SUM(s.amount) FROM statements s JOIN wallets w2 ON w2.id = s.wallet_id WHERE w2.user_id = :userId2 AND s.type = 'credit'"
                + (type != null && !type.isBlank() ? " AND w2.type = :wtype2" : "") + "), 0) as inflow, "
                + "COALESCE((SELECT SUM(s.amount) FROM statements s JOIN wallets w2 ON w2.id = s.wallet_id WHERE w2.user_id = :userId3 AND s.type = 'debit'"
                + (type != null && !type.isBlank() ? " AND w2.type = :wtype3" : "") + "), 0) as outflow "
                + "FROM wallets w " + where;

        Query q = entityManager.createNativeQuery(sql);
        qp.forEach(q::setParameter);
        q.setParameter("userId2", userId);
        q.setParameter("userId3", userId);
        if (type != null && !type.isBlank()) {
            q.setParameter("wtype2", type);
            q.setParameter("wtype3", type);
        }

        Object[] r = (Object[]) q.getSingleResult();
        double closingBalance = ((Number) r[0]).doubleValue();
        double inflow = ((Number) r[1]).doubleValue();
        double outflow = ((Number) r[2]).doubleValue();
        double openingBalance = closingBalance - inflow + outflow;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("opening_balance", openingBalance);
        stats.put("inflow", inflow);
        stats.put("outflow", outflow);
        stats.put("closing_balance", closingBalance);
        return stats;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<Map<String, Object>> getAggregatorAssociations(Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        StringBuilder where = new StringBuilder("WHERE 1=1");
        Map<String, Object> qp = new HashMap<>();

        String search = params.get("search");
        if (search != null && !search.isBlank()) {
            String searchLower = "%" + search.toLowerCase() + "%";
            where.append(
                    " AND (LOWER(u.email) LIKE :search OR LOWER(u.phone_number) LIKE :search OR LOWER(COALESCE(p.first_name,'') || ' ' || COALESCE(p.last_name,'')) LIKE :search"
                            + " OR LOWER(pu.email) LIKE :search OR LOWER(COALESCE(pp.first_name,'') || ' ' || COALESCE(pp.last_name,'')) LIKE :search)");
            qp.put("search", searchLower);
        }

        String type = params.get("type");
        if (type != null && !type.isBlank()) {
            where.append(" AND u.type = :type");
            qp.put("type", type);
        }

        String hasParent = params.get("has_parent");
        if ("yes".equalsIgnoreCase(hasParent)) {
            where.append(" AND u.parent_id IS NOT NULL");
        } else if ("no".equalsIgnoreCase(hasParent)) {
            where.append(" AND u.parent_id IS NULL");
        }

        String aggregatorId = params.get("aggregator_id");
        if (aggregatorId != null && !aggregatorId.isBlank()) {
            where.append(" AND u.parent_id = :aggregatorId");
            qp.put("aggregatorId", Long.parseLong(aggregatorId));
        }

        String sql = """
                SELECT u.id, TRIM(COALESCE(p.first_name, '') || ' ' || COALESCE(p.last_name, '')) as name,
                       u.email, u.phone_number, u.type,
                       u.parent_id,
                       pu.email as parent_email,
                       TRIM(COALESCE(pp.first_name, '') || ' ' || COALESCE(pp.last_name, '')) as parent_name,
                       u.created_at
                FROM users u
                LEFT JOIN profiles p ON p.user_id = u.id
                LEFT JOIN users pu ON pu.id = u.parent_id
                LEFT JOIN profiles pp ON pp.user_id = pu.id
                """ + where + " ORDER BY u.created_at DESC";

        String countSql = """
                SELECT COUNT(*) FROM users u
                LEFT JOIN profiles p ON p.user_id = u.id
                LEFT JOIN users pu ON pu.id = u.parent_id
                LEFT JOIN profiles pp ON pp.user_id = pu.id
                """ + where;

        Query countQuery = entityManager.createNativeQuery(countSql);
        Query dataQuery = entityManager.createNativeQuery(sql);

        qp.forEach(countQuery::setParameter);
        qp.forEach(dataQuery::setParameter);

        long total = ((Number) countQuery.getSingleResult()).longValue();

        dataQuery.setFirstResult(page * limit);
        dataQuery.setMaxResults(limit);

        List<Object[]> rows = dataQuery.getResultList();
        List<Map<String, Object>> dtos = rows.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", lng(row[0]));
            String name = row[1] != null ? row[1].toString().trim() : null;
            m.put("name", (name != null && !name.isBlank()) ? name : str(row[2]));
            m.put("email", str(row[2]));
            m.put("phone_number", str(row[3]));
            m.put("type", str(row[4]));
            m.put("parent_id", lng(row[5]));
            if (row[5] != null) {
                String parentName = row[7] != null ? row[7].toString().trim() : null;
                m.put("parent", Map.of("id", lng(row[5]), "email", str(row[6]) != null ? str(row[6]) : "", "name",
                        (parentName != null && !parentName.isBlank()) ? parentName : str(row[6])));
            } else {
                m.put("parent", null);
            }
            m.put("created_at", row[8] != null ? row[8].toString() : null);
            return m;
        }).toList();

        return new PageImpl<>(dtos, PageRequest.of(page, limit), total);
    }

    public Map<String, Object> getAggregatorAssociationFilters() {
        Map<String, Object> filters = new HashMap<>();
        filters.put("types",
                List.of(Map.of("id", "user", "name", "User"), Map.of("id", "agent", "name", "Agent"),
                        Map.of("id", "aggregator", "name", "Aggregator"),
                        Map.of("id", "super_aggregator", "name", "Super Aggregator"),
                        Map.of("id", "merchant", "name", "Merchant")));
        filters.put("has_parent",
                List.of(Map.of("id", "yes", "name", "Has Parent"), Map.of("id", "no", "name", "No Parent")));
        filters.put("aggregators", listAggregators(null));
        return filters;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listAggregators(String search) {
        StringBuilder sql = new StringBuilder(
                "SELECT u.id, u.email, TRIM(COALESCE(p.first_name, '') || ' ' || COALESCE(p.last_name, '')) as name "
                        + "FROM users u LEFT JOIN profiles p ON p.user_id = u.id "
                        + "WHERE u.type IN ('aggregator', 'super_aggregator')");
        Map<String, Object> qp = new HashMap<>();

        if (search != null && !search.isBlank()) {
            sql.append(
                    " AND (LOWER(u.email) LIKE :search OR LOWER(COALESCE(p.first_name,'') || ' ' || COALESCE(p.last_name,'')) LIKE :search)");
            qp.put("search", "%" + search.toLowerCase() + "%");
        }

        sql.append(" ORDER BY u.email LIMIT 50");

        Query q = entityManager.createNativeQuery(sql.toString());
        qp.forEach(q::setParameter);

        List<Object[]> rows = q.getResultList();
        return rows.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", lng(row[0]).toString());
            String name = row[2] != null ? row[2].toString().trim() : null;
            m.put("name", (name != null && !name.isBlank()) ? name + " (" + str(row[1]) + ")" : str(row[1]));
            return m;
        }).toList();
    }
}

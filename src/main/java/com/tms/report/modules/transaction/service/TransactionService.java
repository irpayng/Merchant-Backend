package com.tms.report.modules.transaction.service;

import com.tms.report.core.exception.AppException;
import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.product.model.Product;
import com.tms.report.modules.product.repository.ProductRepository;
import com.tms.report.modules.status.StatusUtil;
import com.tms.report.modules.transaction.dto.NameCodeRef;
import com.tms.report.modules.transaction.dto.StatusRef;
import com.tms.report.modules.transaction.dto.TransactionDto;
import com.tms.report.modules.transaction.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionService {

    /**
     * Sensitive metadata keys that MUST NOT be returned to the admin UI. Mirrors
     * {@code SENSITIVE_METADATA_KEYS} in the transaction microservice's
     * {@code TransactionView} — keep the two lists in sync.
     *
     * The transaction microservice strips these before serving to the mobile app,
     * but tms-report-java reads {@code transactions.metadata} directly from the
     * replicated database, so we must apply the same filter here before exposing
     * the JSONB blob as {@code transactable} to the dashboard.
     */
    private static final Set<String> SENSITIVE_METADATA_KEYS = Set.of(
            // Card data — PCI DSS sensitive
            "pan", "track2", "icc_data", "icc", "pin_block", "clear_pin_block", "new_pin_block", "card_sequence_number",
            "service_code", "expiry_date", "card_holder_name",
            // Cryptographic keys
            "tsk",
            // Internal terminal/merchant routing data
            "tid_terminal_id", "tid_merchant_id", "tid_merchant_name", "tid_merchant_category_code",
            "tid_merchant_address", "tid_settlement_account", "tid_state_code", "tid_merchant_physical_addr",
            "tid_merchant_address_lga_code",
            // Instant settlement internal fields
            "original_terminal_id", "original_merchant_id", "original_settlement_account",
            // POS entry mode / data codes (internal)
            "pos_entry_mode", "pos_data_code",
            // Processing internals
            "processing_code");

    private final TransactionRepository transactionRepository;
    private final ProductRepository productRepository;
    private final EntityManager entityManager;
    private final MerchantScope merchantScope;

    /**
     * Device serial of the cashier's locked terminal, or {@code null} for an owner
     * (no terminal narrowing). Resolves {@code terminals.id} -> serial; an unknown
     * terminal yields a non-matching sentinel so a mis-scoped cashier sees nothing.
     */
    private String cashierTerminalSerial() {
        Long terminalId = merchantScope.terminalId();
        if (terminalId == null) {
            return null;
        }
        try {
            Object s = entityManager.createNativeQuery("SELECT serial FROM terminals WHERE id = :tid")
                    .setParameter("tid", terminalId).getResultStream().findFirst().orElse(null);
            return s != null ? s.toString() : "__no_serial__";
        } catch (RuntimeException e) {
            return "__no_serial__";
        }
    }

    /**
     * Builds the {@code WHERE} clause (search + filters + dates) shared by the
     * paged listing ({@link #index}) and the CSV export ({@link #exportRows}) so
     * that a download always reflects exactly the same rows the admin is looking at
     * on screen. Mutates {@code qParams} with the bound parameter values.
     */
    /**
     * Appends the free-text / field-specific search predicate. Shared by the paged
     * listing, the export, and the stats summary so a search narrows all three
     * consistently.
     *
     * <p>
     * Predicates on {@code t.*} (reference, metadata fields) and the {@code nin}/
     * {@code tid} sub-selects need no joins. The {@code email}, {@code phone},
     * {@code user_name}, {@code bvn} and generic searches reference {@code u.*} /
     * {@code p2.*} — callers that may run without the users/profiles joins must
     * gate them with {@link #searchNeedsUserJoin}.
     */
    private void appendSearch(StringBuilder where, Map<String, Object> qParams, Map<String, String> params) {
        String search = params.get("search");
        String searchField = params.get("search_field");
        if (search == null || search.isBlank()) {
            return;
        }
        if (searchField != null && !searchField.isBlank()) {
            // Field-specific search
            switch (searchField) {
                case "user_name" -> {
                    String[] words = search.toLowerCase().trim().split("\\s+");
                    String fullName = "LOWER(COALESCE(p2.first_name, '') || ' ' || COALESCE(p2.middle_name, '') || ' ' || COALESCE(p2.last_name, ''))";
                    where.append(" AND (");
                    for (int i = 0; i < words.length; i++) {
                        if (i > 0)
                            where.append(" AND ");
                        String param = "nameWord" + i;
                        where.append(fullName).append(" LIKE :").append(param);
                        qParams.put(param, "%" + words[i] + "%");
                    }
                    where.append(")");
                }
                case "email" -> {
                    where.append(" AND LOWER(u.email) = :searchExact");
                    qParams.put("searchExact", search.toLowerCase());
                }
                case "phone" -> {
                    where.append(" AND LOWER(u.phone_number) = :searchExact");
                    qParams.put("searchExact", search.toLowerCase());
                }
                case "session_id" -> {
                    where.append(" AND t.metadata->>'session_id' = :searchExact");
                    qParams.put("searchExact", search);
                }
                case "account_number" -> {
                    where.append(" AND t.metadata->>'account_number' = :searchExact");
                    qParams.put("searchExact", search);
                }
                case "meter_number" -> {
                    where.append(" AND t.metadata->>'meter_number' = :searchExact");
                    qParams.put("searchExact", search);
                }
                case "rrn" -> {
                    where.append(" AND t.metadata->>'rrn' = :searchExact");
                    qParams.put("searchExact", search);
                }
                case "serial" -> {
                    where.append(" AND t.metadata->>'serial' = :searchExact");
                    qParams.put("searchExact", search);
                }
                case "tid" -> {
                    // Transactions never persist a "tid" metadata key — card
                    // transactions only store the device "serial". The terminal
                    // ID (e.g. 2011E130) lives in the config service's `tids`
                    // table and maps to one or more device serials via
                    // terminal_tid -> terminals. Resolve the TID to its bound
                    // serials and match transactions on metadata->>'serial'.
                    where.append(
                            " AND t.metadata->>'serial' IN (SELECT tm.serial FROM tids td JOIN terminal_tid tt ON tt.tid_id = td.id JOIN terminals tm ON tm.id = tt.terminal_id WHERE td.terminal_id = :searchExact)");
                    qParams.put("searchExact", search);
                }
                case "bank_name" -> {
                    where.append(" AND LOWER(t.metadata->>'bank_name') LIKE :searchLike");
                    qParams.put("searchLike", search.toLowerCase() + "%");
                }
                case "bvn" -> {
                    // BVN lives on users.bvn in the microservice schema — there
                    // is no `bvns` table (see BvnService / UserService). The old
                    // subquery referenced a dead table and errored every search.
                    where.append(" AND u.bvn = :searchExact");
                    qParams.put("searchExact", search);
                }
                case "nin" -> {
                    // KYC `nins` table is not replicated to the super-merchant portal.
                    where.append(" AND t.metadata->>'nin' = :searchExact");
                    qParams.put("searchExact", search);
                }
                default -> {
                    where.append(" AND t.reference LIKE :searchPrefix");
                    qParams.put("searchPrefix", search + "%");
                }
            }
        } else {
            // Default generic search (reference prefix match)
            String[] words = search.toLowerCase().trim().split("\\s+");
            String fullName = "LOWER(COALESCE(p2.first_name, '') || ' ' || COALESCE(p2.middle_name, '') || ' ' || COALESCE(p2.last_name, ''))";
            where.append(
                    " AND (t.reference LIKE :searchPrefix OR LOWER(u.email) LIKE :search OR LOWER(u.phone_number) LIKE :search OR (");
            for (int i = 0; i < words.length; i++) {
                if (i > 0)
                    where.append(" AND ");
                String param = "nameWord" + i;
                where.append(fullName).append(" LIKE :").append(param);
                qParams.put(param, "%" + words[i] + "%");
            }
            where.append("))");
            qParams.put("searchPrefix", search + "%");
            qParams.put("search", "%" + search.toLowerCase() + "%");
        }
    }

    /**
     * Whether the active search references the users/profiles tables (and so needs
     * those joins). Searches on {@code t.*} metadata fields, or the {@code nin}/
     * {@code tid} sub-selects, do not — so a stats query can skip the joins
     * entirely in the common case and stay a single-table aggregate.
     */
    private boolean searchNeedsUserJoin(Map<String, String> params) {
        String search = params.get("search");
        if (search == null || search.isBlank()) {
            return false;
        }
        String field = params.get("search_field");
        if (field == null || field.isBlank()) {
            return true; // generic search matches email / phone / name
        }
        return switch (field) {
            case "email", "phone", "user_name", "bvn" -> true;
            default -> false;
        };
    }

    private StringBuilder buildWhere(Map<String, String> params, Map<String, Object> qParams) {
        StringBuilder where = new StringBuilder("WHERE 1=1");

        appendSearch(where, qParams, params);
        addFilter(where, qParams, params, "status_code", "t.status_code");
        addFilter(where, qParams, params, "status", "t.status_code");
        // Product filter must span legacy aliases. Production was migrated from
        // the camelCase monolith schema, so the same logical product can appear
        // under two product rows (e.g. id=102 code='virtual-funding' active and
        // id=15 code='virtualFunding' legacy). The dropdown only ships active
        // products, so a naive equality on t.product_id misses every migrated
        // row. addProductFilter expands the input to all known aliases.
        addProductFilter(where, qParams, params, "product_id", "t.product_id", "t.product_code");
        addProductFilter(where, qParams, params, "product", "t.product_id", "t.product_code");
        addFilter(where, qParams, params, "product_code", "t.product_code");
        // Provider filters intentionally omitted — provider data is not exposed in
        // the super-merchant portal and the `providers` table is not replicated here.
        addFilter(where, qParams, params, "channel_id", "t.channel");
        addFilter(where, qParams, params, "channel", "t.channel");
        addFilter(where, qParams, params, "payment_method_id", "t.payment_method");
        addFilter(where, qParams, params, "payment_method", "t.payment_method");
        addFilter(where, qParams, params, "user_id", "t.user_id");
        // Terminal scope: match a device serial against either the dedicated
        // transactions.terminal_id column or the card metadata `serial` — both are
        // populated depending on the transaction source, so an OR covers every row
        // done on that physical terminal. Powers the terminal-detail transactions tab.
        String terminalSerial = params.get("terminal_serial");
        if (terminalSerial != null && !terminalSerial.isBlank()) {
            where.append(" AND (t.terminal_id = :terminal_serial OR t.metadata->>'serial' = :terminal_serial)");
            qParams.put("terminal_serial", terminalSerial.trim());
        }
        addFilter(where, qParams, params, "location_state", "t.location_state");
        addFilter(where, qParams, params, "location_lga", "t.location_lga");
        QueryFilterHelper.applyDates(where, qParams, params, "t.created_at");

        // Reconciliation queue: card transactions whose in-doubt reversal leg could
        // not be confirmed after a provider failover (the cardholder may have been
        // debited at the failed switch with the reversal unconfirmed). Flagged on the
        // parent transaction's metadata by the processor — pass indoubt_reversal=true
        // to surface only these.
        if ("true".equalsIgnoreCase(params.get("indoubt_reversal"))) {
            where.append(" AND t.metadata->>'indoubt_reversal_unconfirmed' = 'true'");
        }
        // Per-bank tenant scope: restrict to the bank's direct merchants.
        merchantScope.appendTransactionScope(where, qParams, "t.user_id", cashierTerminalSerial(), "t.terminal_id",
                "t.metadata->>'serial'");
        // Exclude manual funding — administrative wallet adjustments are not
        // customer-facing transactions and should only appear on statements.
        where.append(" AND COALESCE(t.product_code, '') <> 'manual-funding'");
        return where;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<TransactionDto> index(Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "10"));

        Map<String, Object> qParams = new HashMap<>();
        StringBuilder where = buildWhere(params, qParams);

        String sql = """
                SELECT t.id, t.reference,
                       t.amount, t.status_code, t.created_at,
                       pr.id as prod_id, pr.name as prod_name, COALESCE(pr.code, t.product_code) as prod_code,
                       NULL as prov_id, NULL as prov_name,
                       NULL as prov_code,
                       t.channel, t.payment_method,
                       t.service_fee, t.agent_commission, t.aggregator_commission,
                       t.super_aggregator_commission, t.company_commission, t.amount_to_pay,
                       0 as provider_cost,
                       (t.reversal_blob IS NOT NULL AND COALESCE(t.metadata->>'card_reversal_exhausted','') <> 'true') as has_reversal,
                       t.metadata->>'rrn' as rrn,
                       COALESCE(t.metadata->>'card_holder_name', t.metadata->>'card_holder', t.metadata->>'account_name', t.metadata->>'beneficiary_name') as card_holder,
                       t.metadata->>'pan' as masked_pan,
                       t.metadata->>'stan' as stan,
                       t.metadata->>'auth_code' as auth_code,
                       COALESCE(t.metadata->>'serial', t.terminal_id) as terminal_serial
                FROM transactions t
                LEFT JOIN products pr ON pr.id = t.product_id
                """
                + where + " ORDER BY t.created_at DESC";

        String countSql = "SELECT COUNT(*) FROM transactions t " + where;
        Query countQ = entityManager.createNativeQuery(countSql);
        qParams.forEach(countQ::setParameter);
        long total = ((Number) countQ.getSingleResult()).longValue();

        Query q = entityManager.createNativeQuery(sql);
        qParams.forEach(q::setParameter);
        q.setFirstResult(page * limit);
        q.setMaxResults(limit);

        List<Object[]> rows = q.getResultList();
        List<TransactionDto> dtos = rows.stream().map(this::mapRow).toList();

        return new PageImpl<>(dtos, PageRequest.of(page, limit), total);
    }

    /**
     * Distinct product codes matching the current export filters. Used by the
     * download to decide the column layout (transfer/card/electricity/... vs the
     * general superset) without having to pre-scan the full result set — so the
     * heavy data read can stay a single forward-only stream.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<String> exportProductCodes(Map<String, String> params) {
        Map<String, Object> qParams = new HashMap<>();
        StringBuilder where = buildWhere(params, qParams);
        String sql = "SELECT DISTINCT COALESCE(pr.code, t.product_code) FROM transactions t "
                + "LEFT JOIN users u ON u.id = t.user_id LEFT JOIN profiles p2 ON p2.user_id = u.id "
                + "LEFT JOIN products pr ON pr.id = t.product_id " + where;
        Query q = entityManager.createNativeQuery(sql);
        qParams.forEach(q::setParameter);
        List<Object> codes = q.getResultList();
        List<String> out = new ArrayList<>(codes.size());
        for (Object c : codes) {
            out.add(c != null ? c.toString() : null);
        }
        return out;
    }

    /**
     * Streams the flattened export rows to {@code consumer} one at a time using a
     * forward-only server-side cursor, so memory stays flat regardless of how many
     * rows match — the result set is never fully materialized in heap. Honours the
     * same search + filters as {@link #index} via {@link #buildWhere}. An optional
     * {@code limit} param caps the row count; absent/<=0 means no cap.
     *
     * <p>
     * The consumer runs inside the read-only transaction while the cursor is open,
     * so it must do cheap work (e.g. append to a streaming workbook) and must not
     * block on the client socket — keep network writes outside this call.
     *
     * <p>
     * The PAN is only ever emitted masked; the full card number is never exposed
     * (PCI DSS).
     */
    @Transactional(readOnly = true)
    public void streamExportRows(Map<String, String> params, Consumer<Map<String, Object>> consumer) {
        int limit = 0;
        try {
            limit = Integer.parseInt(params.getOrDefault("limit", "0"));
        } catch (NumberFormatException ignored) {
            limit = 0;
        }

        Map<String, Object> qParams = new HashMap<>();
        StringBuilder where = buildWhere(params, qParams);

        String sql = EXPORT_SELECT + where + " ORDER BY t.created_at DESC";

        Session session = entityManager.unwrap(Session.class);
        NativeQuery<Object[]> q = session.createNativeQuery(sql, Object[].class);
        qParams.forEach(q::setParameter);
        q.setReadOnly(true);
        // Fetch in batches via a server-side cursor (requires the tx's autocommit
        // off, which @Transactional provides) instead of buffering the whole
        // result set in the JDBC driver.
        q.setFetchSize(500);
        if (limit > 0) {
            q.setMaxResults(limit);
        }

        try (ScrollableResults<Object[]> results = q.scroll(ScrollMode.FORWARD_ONLY)) {
            while (results.next()) {
                consumer.accept(mapExportRow(results.get()));
            }
        }
    }

    /** Column list (no WHERE/ORDER/LIMIT) shared by the streaming export. */
    private static final String EXPORT_SELECT = """
            SELECT t.reference, t.amount, t.status_code, t.created_at,
                   COALESCE(pr.code, t.product_code) as prod_code, pr.name as prod_name,
                   NULL as prov_name,
                   t.channel, t.payment_method,
                   u.email,
                   TRIM(COALESCE(p2.first_name, '') || ' ' || COALESCE(p2.last_name, '')) as user_name,
                   t.metadata->>'initiator_name' as initiator_name,
                   t.metadata->>'session_id' as session_id,
                   t.metadata->>'rrn' as rrn,
                   t.metadata->>'stan' as stan,
                   t.metadata->>'auth_code' as auth_code,
                   t.metadata->>'bank_name' as bank_name,
                   t.metadata->>'account_number' as account_number,
                   t.metadata->>'account_name' as account_name,
                   t.metadata->>'beneficiary' as beneficiary,
                   t.metadata->>'beneficiary_name' as beneficiary_name,
                   t.metadata->>'name' as recipient_name,
                   t.metadata->>'phone_number' as phone_number,
                   t.metadata->>'network' as network,
                   t.metadata->>'disco' as disco,
                   t.metadata->>'meter_number' as meter_number,
                   t.metadata->>'meter_type' as meter_type,
                   t.metadata->>'token' as token,
                   t.metadata->>'units' as units,
                   t.metadata->>'pan' as pan,
                   t.terminal_id,
                   t.service_fee, t.agent_commission, t.aggregator_commission,
                   t.super_aggregator_commission, t.company_commission, t.amount_to_pay,
                   0 as provider_cost
            FROM transactions t
            LEFT JOIN users u ON u.id = t.user_id
            LEFT JOIN profiles p2 ON p2.user_id = u.id
            LEFT JOIN products pr ON pr.id = t.product_id
            """;

    private Map<String, Object> mapExportRow(Object[] r) {
        Map<String, Object> m = new LinkedHashMap<>();
        String prodCode = str(r[4]);
        String prodName = str(r[5]);
        if (prodName == null && prodCode != null) {
            prodName = capitalize(prodCode.replace("_", " ").replace("-", " "));
        }
        String userName = str(r[10]);
        if (userName != null && userName.isBlank()) {
            userName = null;
        }
        if (userName == null) {
            userName = str(r[11]); // initiator_name (admin transfers)
        }
        String beneficiary = firstNonBlank(str(r[20]), str(r[19]), str(r[21]));

        m.put("reference", str(r[0]));
        m.put("amount", r[1] != null ? new BigDecimal(r[1].toString()).toPlainString() : null);
        m.put("status", StatusUtil.getStatusName(str(r[2])));
        m.put("product_code", prodCode);
        m.put("product", prodName);
        m.put("provider", str(r[6]));
        m.put("channel", capitalize(str(r[7])));
        m.put("payment_method", capitalize(str(r[8])));
        m.put("user_email", str(r[9]));
        m.put("user_name", userName);
        m.put("session_id", str(r[12]));
        m.put("rrn", str(r[13]));
        m.put("stan", str(r[14]));
        m.put("auth_code", str(r[15]));
        m.put("bank_name", str(r[16]));
        m.put("account_number", str(r[17]));
        m.put("account_name", str(r[18]));
        m.put("beneficiary", beneficiary);
        m.put("phone_number", str(r[22]));
        m.put("network", str(r[23]));
        m.put("disco", str(r[24]));
        m.put("meter_number", str(r[25]));
        m.put("meter_type", str(r[26]));
        m.put("token", str(r[27]));
        m.put("units", str(r[28]));
        m.put("masked_pan", maskPan(str(r[29])));
        m.put("terminal_serial", str(r[30]));
        m.put("service_fee", plainAmount(r[31]));
        m.put("agent_commission", plainAmount(r[32]));
        m.put("aggregator_commission", plainAmount(r[33]));
        m.put("super_aggregator_commission", plainAmount(r[34]));
        m.put("company_commission", plainAmount(r[35]));
        m.put("amount_to_pay", plainAmount(r[36]));
        m.put("provider_cost", plainAmount(r[37]));
        m.put("created_at", r[3] != null ? toLocalDateTime(r[3]).toString() : null);
        return m;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return null;
        }
        return "**** **** **** " + pan.substring(pan.length() - 4);
    }

    /**
     * Resolve a transaction ID or reference to its reference string.
     */
    @Transactional(readOnly = true)
    public String resolveReference(String idOrRef) {
        String sql;
        Object paramValue;
        try {
            paramValue = Long.parseLong(idOrRef);
            sql = "SELECT t.reference FROM transactions t WHERE t.id = :val";
        } catch (NumberFormatException e) {
            return idOrRef; // Already a reference
        }
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("val", paramValue);
        try {
            return (String) q.getSingleResult();
        } catch (Exception e) {
            throw new AppException("Transaction not found: " + idOrRef, HttpStatus.NOT_FOUND);
        }
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> show(String idOrRef) {
        String sql = "SELECT t.id, t.reference, "
                + "t.amount, t.status_code, t.status_message, t.created_at, t.user_id, "
                + "t.product_id, t.provider_id, t.channel, t.payment_method, "
                + "t.service_fee, t.agent_commission, t.aggregator_commission, t.company_commission, t.amount_to_pay, "
                + "NULL as prov_id, NULL as prov_name, " + "NULL as prov_code, "
                + "t.config_context, t.location_state, t.location_lga, t.product_code, t.metadata, "
                + "COALESCE(t.super_aggregator_commission, 0) as super_aggregator_commission, " + "0 as provider_cost "
                + "FROM transactions t";
        Object paramValue;
        try {
            paramValue = Long.parseLong(idOrRef);
            sql += " WHERE t.id = :val";
        } catch (NumberFormatException e) {
            paramValue = idOrRef;
            sql += " WHERE t.reference = :val";
        }

        // Per-bank tenant scope: a record outside the caller's bank resolves to
        // no row → 404, so a bank user can't fetch another bank's transaction.
        Map<String, Object> scopeBinds = new HashMap<>();
        StringBuilder scope = new StringBuilder();
        merchantScope.appendUserScope(scope, scopeBinds, "t.user_id");
        sql += scope.toString();

        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("val", paramValue);
        scopeBinds.forEach(q::setParameter);

        // Retry up to 3 times with short delays to handle PostgreSQL logical
        // replication lag — recently-created transactions may not be in the
        // replica yet when an admin clicks a realtime row.
        Object[] t = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                t = (Object[]) q.getSingleResult();
                break;
            } catch (jakarta.persistence.NoResultException e) {
                if (attempt == 2)
                    throw new AppException("Transaction not found: " + idOrRef, HttpStatus.NOT_FOUND);
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }

        Long txId = lng(t[0]);
        String reference = str(t[1]);
        Long userId = lng(t[6]);
        String statusCode = str(t[3]);
        String channel = str(t[9]);
        String paymentMethod = str(t[10]);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", txId);
        result.put("reference", reference);
        result.put("transactable", sanitizeTransactable(parseMetadataJsonb(t[23])));
        result.put("user", userId != null ? loadFullUser(userId) : null);
        // Product: try products table first, fall back to product_code
        Map<String, Object> productMap = loadRowById("products", lng(t[7]));
        if (productMap == null) {
            String prodCode = str(t[22]);
            if (prodCode != null) {
                String prodName = capitalize(prodCode.replace("_", " ").replace("-", " "));
                productMap = Map.of("name", prodName, "code", prodCode);
            }
        }
        result.put("product", productMap);
        // Provider: from JOIN, with fallback to provider_code
        Long provId = lng(t[16]);
        String provName = str(t[17]);
        String provCode = str(t[18]);
        result.put("provider",
                (provId != null || provName != null)
                        ? Map.of("id", provId != null ? provId : 0L, "name", provName != null ? provName : "", "code",
                                provCode != null ? provCode : "")
                        : null);
        // Channel: build from channel string
        result.put("channel", channel != null ? Map.of("name", capitalize(channel), "code", channel) : null);
        // Payment method: build from payment_method string
        result.put("payment_method",
                paymentMethod != null ? Map.of("name", capitalize(paymentMethod), "code", paymentMethod) : null);
        // Traffic/logs (ingress, client, ISO, pipelines) are intentionally NOT
        // exposed in the super-merchant portal — those are internal admin views.
        result.put("amount", t[2] != null ? new BigDecimal(t[2].toString()).toPlainString() : null);
        // Status: build from status_code string
        result.put("status", StatusUtil.toStatusMap(statusCode));
        // Cost breakdowns: read inline from transaction row
        Map<String, Object> costs = new LinkedHashMap<>();
        costs.put("service_fee", t[11] != null ? new BigDecimal(t[11].toString()).toPlainString() : "0");
        costs.put("agent_commission", t[12] != null ? new BigDecimal(t[12].toString()).toPlainString() : "0");
        costs.put("aggregator_commission", t[13] != null ? new BigDecimal(t[13].toString()).toPlainString() : "0");
        costs.put("super_aggregator_commission",
                t[24] != null ? new BigDecimal(t[24].toString()).toPlainString() : "0");
        costs.put("company_commission", t[14] != null ? new BigDecimal(t[14].toString()).toPlainString() : "0");
        costs.put("provider_cost", t[25] != null ? new BigDecimal(t[25].toString()).toPlainString() : "0");
        costs.put("amount_to_pay", t[15] != null ? new BigDecimal(t[15].toString()).toPlainString() : "0");
        result.put("cost_breakdowns", costs);
        result.put("config_context", parseJsonb(t[19]));
        result.put("location_state", str(t[20]));
        result.put("location_lga", str(t[21]));
        result.put("created_at", formatTime(t[5]));

        return result;
    }

    // -- Show helper methods --

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadRowById(String table, Long id) {
        if (id == null)
            return null;
        try {
            return queryAsMap("SELECT * FROM " + table + " WHERE id = :id", Map.of("id", id));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> queryAsMap(String sql, Map<String, Object> params) {
        var session = entityManager.unwrap(org.hibernate.Session.class);
        var nq = session.createNativeQuery(sql, java.util.Map.class);
        params.forEach(nq::setParameter);
        try {
            return (Map<String, Object>) nq.getSingleResult();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> queryAsList(String sql, Map<String, Object> params) {
        var session = entityManager.unwrap(org.hibernate.Session.class);
        var nq = session.createNativeQuery(sql, java.util.Map.class);
        params.forEach(nq::setParameter);
        return (List<Map<String, Object>>) (List<?>) nq.getResultList();
    }

    /**
     * Load minimal user information for transaction details. Only returns id and
     * name — the fields actually displayed in the merchant UI. Avoids exposing
     * sensitive user data (email, BVN, tier, address, etc.).
     */
    private Map<String, Object> loadFullUser(Long userId) {
        try {
            Map<String, Object> profile = queryAsMap(
                    "SELECT first_name, middle_name, last_name FROM profiles WHERE user_id = :uid",
                    Map.of("uid", userId));

            String name = null;
            if (profile != null) {
                name = Stream
                        .of(str(profile.get("first_name")), str(profile.get("middle_name")),
                                str(profile.get("last_name")))
                        .filter(Objects::nonNull).reduce((a, b) -> a + " " + b).orElse(null);
            }

            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", userId);
            user.put("name", name);
            return user;
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> loadPipelines(String reference, String transactionStatus) {
        if (reference == null)
            return List.of();
        List<Map<String, Object>> rows = queryAsList("SELECT * FROM pipelines WHERE reference = :ref ORDER BY sequence",
                Map.of("ref", (Object) reference));

        if (rows.isEmpty()) {
            return rows;
        }

        // Derive a step-level status so the UI can distinguish success / failed /
        // pending without having to cross-reference the transaction. The rules are:
        // - A non-null error column always means the step failed.
        // - For a completed transaction, all other steps are success.
        // - For a failed/reversed transaction with no step-level error, flag the
        // last recorded step as failed (that's where the pipeline actually broke
        // before the requery/reversal was written) so the timeline doesn't read
        // as all-green.
        // - For a processing/claimed transaction with no step-level error, show the
        // last step as pending instead of success.
        boolean terminalFailure = "failed".equalsIgnoreCase(transactionStatus)
                || "reversed".equalsIgnoreCase(transactionStatus);
        boolean stillProcessing = "processing".equalsIgnoreCase(transactionStatus)
                || "claimed".equalsIgnoreCase(transactionStatus);

        int lastIndex = rows.size() - 1;
        boolean anyErrorOnLaterStep = false;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            boolean hasError = row.get("error") != null && !String.valueOf(row.get("error")).isBlank();
            String derived;
            if (hasError) {
                derived = "failed";
                anyErrorOnLaterStep = true;
            } else if (i == lastIndex && !anyErrorOnLaterStep && terminalFailure) {
                derived = "failed";
            } else if (i == lastIndex && !anyErrorOnLaterStep && stillProcessing) {
                derived = "pending";
            } else {
                derived = "success";
            }
            row.put("status", derived);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadIngressRequests(String reference) {
        if (reference == null)
            return List.of();
        // ingress_requests has request + response in same row.
        // The UI expects: { reference, method, url, ip, user_agent, controller, action,
        // body, created_at,
        // ingress_response: { status, status_message, duration, body } }
        List<Map<String, Object>> rows = queryAsList(
                "SELECT * FROM ingress_requests WHERE reference = :ref ORDER BY created_at",
                Map.of("ref", (Object) reference));

        return rows.stream().map(row -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("reference", row.get("reference"));
            mapped.put("method", row.get("method"));
            mapped.put("url", row.get("url"));
            mapped.put("ip", row.get("ip"));
            mapped.put("user_agent", row.get("user_agent"));
            mapped.put("controller", row.get("service"));
            mapped.put("action", null);
            mapped.put("body", row.get("request_body"));
            mapped.put("data", row.get("request_body"));
            mapped.put("created_at", row.get("created_at"));

            Map<String, Object> ingressResponse = new LinkedHashMap<>();
            ingressResponse.put("status", row.get("response_status"));
            ingressResponse.put("status_message", null);
            ingressResponse.put("duration", row.get("duration_ms"));
            ingressResponse.put("body", row.get("response_body"));
            mapped.put("ingress_response", ingressResponse);

            return mapped;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadClientRequests(String reference) {
        if (reference == null)
            return List.of();
        // client_requests has request_body + response_body in same row.
        // The UI expects: { type, url, method, data, source_ip, created_at,
        // client_response: { status, duration, data } }
        List<Map<String, Object>> rows = queryAsList(
                "SELECT * FROM client_requests WHERE reference = :ref ORDER BY created_at",
                Map.of("ref", (Object) reference));

        return rows.stream().map(row -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("type", row.getOrDefault("service", row.get("provider")));
            mapped.put("url", row.get("url"));
            mapped.put("method", row.get("method"));
            mapped.put("data", row.get("request_body"));
            mapped.put("source_ip", null);
            mapped.put("created_at", row.get("created_at"));

            Map<String, Object> clientResponse = new LinkedHashMap<>();
            clientResponse.put("status", row.get("response_status"));
            clientResponse.put("duration", row.get("duration_ms"));
            clientResponse.put("data", row.get("response_body"));
            mapped.put("client_response", clientResponse);

            return mapped;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadIsoTraffic(String reference) {
        if (reference == null)
            return List.of();
        // New schema: iso_requests has both request and response fields in same row
        // No separate iso_responses table.
        // The UI expects: { mti, class, fields, iso, created_at,
        // iso_response: { code, message, fields, iso } }
        List<Map<String, Object>> rows = queryAsList(
                "SELECT * FROM iso_requests WHERE reference = :ref ORDER BY created_at",
                Map.of("ref", (Object) reference));

        return rows.stream().map(row -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", row.get("id"));
            mapped.put("reference", row.get("reference"));
            mapped.put("mti", row.get("mti"));
            mapped.put("class", row.get("action"));
            mapped.put("provider", row.get("provider"));
            mapped.put("fields", row.get("request_fields"));
            mapped.put("iso", row.get("request_hex"));
            mapped.put("created_at", row.get("created_at"));

            String responseCode = row.get("response_code") != null ? row.get("response_code").toString() : null;
            if (responseCode != null) {
                Map<String, Object> isoResp = new LinkedHashMap<>();
                isoResp.put("code", responseCode);
                isoResp.put("message", row.get("response_message"));
                isoResp.put("fields", row.get("response_fields"));
                isoResp.put("iso", row.get("response_hex"));
                mapped.put("iso_response", isoResp);
            } else {
                mapped.put("iso_response", null);
            }

            return mapped;
        }).toList();
    }

    private String formatTime(Object o) {
        if (o == null)
            return null;
        LocalDateTime ldt = toLocalDateTime(o);
        if (ldt != null)
            return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return o.toString().substring(0, Math.min(16, o.toString().length()));
    }

    private LocalDateTime toLocalDateTime(Object o) {
        if (o == null)
            return null;
        if (o instanceof java.sql.Timestamp ts)
            return ts.toLocalDateTime();
        if (o instanceof LocalDateTime ldt)
            return ldt;
        if (o instanceof java.time.Instant inst)
            return LocalDateTime.ofInstant(inst, java.time.ZoneId.systemDefault());
        if (o instanceof java.time.OffsetDateTime odt)
            return odt.toLocalDateTime();
        try {
            return LocalDateTime
                    .parse(o.toString().replace(" ", "T").substring(0, Math.min(19, o.toString().length())));
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSummary(Map<String, String> params) {
        LocalDateTime[] dates = parseDates(params);

        if (dates[0] == null && dates[1] == null) {
            dates[0] = LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay();
            dates[1] = LocalDateTime.now().withDayOfMonth(1).plusMonths(1).minusDays(1).toLocalDate().atTime(23, 59,
                    59);
        }

        // Apply the same free-text search as the listing so the stat cards reflect
        // exactly the rows the user is looking at. The users/profiles joins are only
        // added when the search actually targets them (name/email/phone/bvn) —
        // otherwise this stays a single-table aggregate over `transactions`.
        boolean joinUsers = searchNeedsUserJoin(params);
        StringBuilder sql = new StringBuilder(
                "SELECT t.status_code, COUNT(*) as count, COALESCE(SUM(t.amount), 0) as total FROM transactions t");
        if (joinUsers) {
            sql.append(" LEFT JOIN users u ON u.id = t.user_id LEFT JOIN profiles p2 ON p2.user_id = u.id");
        }
        sql.append(" WHERE 1=1");
        Map<String, Object> qp = new HashMap<>();
        if (dates[0] != null) {
            sql.append(" AND t.created_at >= :s");
            qp.put("s", dates[0]);
        }
        if (dates[1] != null) {
            sql.append(" AND t.created_at <= :e");
            qp.put("e", dates[1]);
        }
        String userId = params.get("user_id");
        if (userId != null && !userId.isBlank()) {
            sql.append(" AND t.user_id = :uid");
            qp.put("uid", Long.parseLong(userId));
        }
        // Keep the terminal-scoped stat cards in step with the listing (see
        // buildWhere).
        String terminalSerial = params.get("terminal_serial");
        if (terminalSerial != null && !terminalSerial.isBlank()) {
            sql.append(" AND (t.terminal_id = :terminal_serial OR t.metadata->>'serial' = :terminal_serial)");
            qp.put("terminal_serial", terminalSerial.trim());
        }
        appendSearch(sql, qp, params);
        addFilter(sql, qp, params, "status", "t.status_code");
        addFilter(sql, qp, params, "status_code", "t.status_code");
        addProductFilter(sql, qp, params, "product", "t.product_id", "t.product_code");
        addProductFilter(sql, qp, params, "product_id", "t.product_id", "t.product_code");
        addFilter(sql, qp, params, "channel", "t.channel");
        addFilter(sql, qp, params, "channel_id", "t.channel");
        addFilter(sql, qp, params, "payment_method", "t.payment_method");
        addFilter(sql, qp, params, "payment_method_id", "t.payment_method");
        merchantScope.appendTransactionScope(sql, qp, "t.user_id", cashierTerminalSerial(), "t.terminal_id",
                "t.metadata->>'serial'");
        // Exclude manual funding — administrative wallet adjustments are not
        // customer-facing transactions and should only appear on statements.
        sql.append(" AND COALESCE(t.product_code, '') <> 'manual-funding'");
        sql.append(" GROUP BY t.status_code");

        Query q = entityManager.createNativeQuery(sql.toString());
        qp.forEach(q::setParameter);
        List<Object[]> rows = q.getResultList();

        Map<String, Object> result = new LinkedHashMap<>();
        long totalCount = 0;
        double totalAmount = 0;
        Map<String, Map<String, Object>> statusMap = new LinkedHashMap<>();

        for (Object[] row : rows) {
            String code = row[0] != null ? (String) row[0] : "unknown";
            long count = ((Number) row[1]).longValue();
            double amount = ((Number) row[2]).doubleValue();
            totalCount += count;
            totalAmount += amount;
            statusMap.put(code, Map.of("count", count, "total", amount));
        }

        result.put("total", Map.of("count", totalCount, "total", totalAmount, "percentage", 100));
        double finalTotal = totalAmount > 0 ? totalAmount : 1;
        statusMap.forEach((code, data) -> {
            double amt = (double) data.get("total");
            double pct = Math.round((amt / finalTotal) * 10000.0) / 100.0;
            result.put(code, Map.of("count", data.get("count"), "total", amt, "percentage", pct));
        });

        for (String s : List.of("completed", "processing", "failed", "reversed")) {
            result.putIfAbsent(s, Map.of("count", 0L, "total", 0.0, "percentage", 0.0));
        }

        return result;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getSummaryByProduct(Map<String, String> params) {
        LocalDateTime[] dates = parseDates(params);
        StringBuilder sql = new StringBuilder("""
                SELECT t.product_id as id, p.name,
                       COALESCE(SUM(CASE WHEN t.status_code != 'failed' THEN t.amount ELSE 0 END), 0) as total,
                       COALESCE(SUM(CASE WHEN t.status_code = 'completed' THEN t.amount ELSE 0 END), 0) as successful,
                       COALESCE(SUM(CASE WHEN t.status_code = 'failed' THEN t.amount ELSE 0 END), 0) as failed
                FROM transactions t
                JOIN products p ON p.id = t.product_id
                WHERE 1=1""");
        Map<String, Object> qp = new HashMap<>();
        if (dates[0] != null) {
            sql.append(" AND t.created_at >= :s");
            qp.put("s", dates[0]);
        }
        if (dates[1] != null) {
            sql.append(" AND t.created_at <= :e");
            qp.put("e", dates[1]);
        }
        sql.append(" GROUP BY t.product_id, p.name");

        Query q = entityManager.createNativeQuery(sql.toString());
        qp.forEach(q::setParameter);

        List<Object[]> rows = q.getResultList();
        return rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ((Number) row[0]).longValue());
            item.put("name", row[1] != null ? row[1].toString() : null);
            item.put("total", ((Number) row[2]).doubleValue());
            item.put("successful", ((Number) row[3]).doubleValue());
            item.put("failed", ((Number) row[4]).doubleValue());
            return item;
        }).toList();
    }

    public Map<String, Object> filters() {
        Map<String, Object> result = new LinkedHashMap<>();
        // Statuses from static utility (no statuses table)
        result.put("statuses", StatusUtil.getTransactionStatuses());
        result.put("products", productRepository.findAllValid());
        // Provider list intentionally omitted — provider data is not exposed in the
        // super-merchant portal.
        result.put("providers", java.util.List.of());
        // Channels: return distinct channel strings from transactions
        result.put("channels", getDistinctChannels());
        // Payment methods: return distinct payment_method strings from transactions
        result.put("payment_methods", getDistinctPaymentMethods());
        // Location: return distinct states and LGAs from transactions
        result.put("states", getDistinctLocationStates());
        result.put("lgas", getDistinctLocationLgas());
        // Reconciliation queue: card transactions with an unconfirmed in-doubt
        // reversal after provider failover. Single option that maps to
        // indoubt_reversal=true (see buildWhere).
        result.put("reconciliation",
                List.of(Map.<String, Object>of("id", "true", "name", "Unconfirmed card reversal", "code", "true")));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getDistinctChannels() {
        try {
            List<Object> rows = entityManager
                    .createNativeQuery(
                            "SELECT DISTINCT channel FROM transactions WHERE channel IS NOT NULL ORDER BY channel")
                    .getResultList();
            return rows.stream().map(code -> Map.<String, Object>of("id", code.toString(), "name",
                    capitalize(code.toString()), "code", code.toString())).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getDistinctPaymentMethods() {
        try {
            List<Object> rows = entityManager.createNativeQuery(
                    "SELECT DISTINCT payment_method FROM transactions WHERE payment_method IS NOT NULL ORDER BY payment_method")
                    .getResultList();
            return rows.stream().map(code -> Map.<String, Object>of("id", code.toString(), "name",
                    capitalize(code.toString()), "code", code.toString())).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getDistinctLocationStates() {
        try {
            List<Object> rows = entityManager.createNativeQuery(
                    "SELECT DISTINCT location_state FROM transactions WHERE location_state IS NOT NULL AND location_state != '' ORDER BY location_state")
                    .getResultList();
            return rows.stream().map(s -> Map.<String, Object>of("id", s.toString(), "name", capitalize(s.toString())))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getDistinctLocationLgas() {
        try {
            List<Object> rows = entityManager.createNativeQuery(
                    "SELECT DISTINCT location_lga FROM transactions WHERE location_lga IS NOT NULL AND location_lga != '' ORDER BY location_lga")
                    .getResultList();
            return rows.stream().map(s -> Map.<String, Object>of("id", s.toString(), "name", capitalize(s.toString())))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> getChannelChart(Map<String, String> params) {
        LocalDateTime[] dates = parseDates(params);

        // Get distinct channels from transactions (no channels table)
        List<String> channels = getDistinctChannelCodes();

        StringBuilder sql = new StringBuilder(
                "SELECT t.channel, t.status_code, COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.status_code != 'failed' AND t.channel IS NOT NULL");
        Map<String, Object> qp = new HashMap<>();
        if (dates[0] != null) {
            sql.append(" AND t.created_at >= :s");
            qp.put("s", dates[0]);
        }
        if (dates[1] != null) {
            sql.append(" AND t.created_at <= :e");
            qp.put("e", dates[1]);
        }
        sql.append(" GROUP BY t.channel, t.status_code");
        Query q = entityManager.createNativeQuery(sql.toString());
        qp.forEach(q::setParameter);
        List<Object[]> rows = q.getResultList();

        Map<String, Map<String, Double>> dataMap = new HashMap<>();
        for (Object[] row : rows) {
            String channelCode = (String) row[0];
            String statusCode = (String) row[1];
            double total = ((Number) row[2]).doubleValue();
            dataMap.computeIfAbsent(channelCode, k -> new HashMap<>()).put(statusCode, total);
        }

        // Filter out "manual" channel
        List<String> filteredChannels = channels.stream().filter(c -> !"manual".equalsIgnoreCase(c)).toList();

        List<String> categories = filteredChannels.stream().map(this::capitalize).toList();
        List<String> statusCodes = List.of("processing", "completed", "reversed");
        List<Map<String, Object>> series = new ArrayList<>();
        for (String statusCode : statusCodes) {
            List<Number> data = new ArrayList<>();
            for (String ch : filteredChannels) {
                double val = dataMap.getOrDefault(ch, Map.of()).getOrDefault(statusCode, 0.0);
                data.add(val);
            }
            series.add(Map.of("name", StatusUtil.getStatusName(statusCode), "data", data));
        }

        return Map.of("categories", categories, "series", series);
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> getProductChart(Map<String, String> params) {
        LocalDateTime[] dates = parseDates(params);

        List<Product> products = productRepository.findAllValid();
        List<String> statusCodes = List.of("processing", "completed", "failed", "reversed");

        StringBuilder sql = new StringBuilder(
                "SELECT t.product_id, t.status_code, COALESCE(SUM(t.amount), 0) FROM transactions t WHERE 1=1");
        Map<String, Object> qp = new HashMap<>();
        if (dates[0] != null) {
            sql.append(" AND t.created_at >= :s");
            qp.put("s", dates[0]);
        }
        if (dates[1] != null) {
            sql.append(" AND t.created_at <= :e");
            qp.put("e", dates[1]);
        }
        sql.append(" GROUP BY t.product_id, t.status_code");
        Query q = entityManager.createNativeQuery(sql.toString());
        qp.forEach(q::setParameter);
        List<Object[]> rows = q.getResultList();

        Map<Long, Map<String, Double>> dataMap = new HashMap<>();
        for (Object[] row : rows) {
            long productId = ((Number) row[0]).longValue();
            String statusCode = (String) row[1];
            double total = ((Number) row[2]).doubleValue();
            dataMap.computeIfAbsent(productId, k -> new HashMap<>()).put(statusCode, total);
        }

        List<String> categories = products.stream().map(Product::getName).toList();
        List<Map<String, Object>> series = new ArrayList<>();
        for (String statusCode : statusCodes) {
            List<Number> data = new ArrayList<>();
            for (var product : products) {
                double val = dataMap.getOrDefault(product.getId(), Map.of()).getOrDefault(statusCode, 0.0);
                data.add(val);
            }
            series.add(Map.of("name", StatusUtil.getStatusName(statusCode), "data", data));
        }

        return Map.of("categories", categories, "series", series);
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPaymentMethodChart(Map<String, String> params) {
        LocalDateTime[] dates = parseDates(params);

        // Get distinct payment methods from transactions (no payment_methods table)
        List<String> paymentMethods = getDistinctPaymentMethodCodes();
        List<String> statusCodes = List.of("processing", "completed", "failed", "reversed");

        StringBuilder sql = new StringBuilder(
                "SELECT t.payment_method, t.status_code, COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.payment_method IS NOT NULL");
        Map<String, Object> qp = new HashMap<>();
        if (dates[0] != null) {
            sql.append(" AND t.created_at >= :s");
            qp.put("s", dates[0]);
        }
        if (dates[1] != null) {
            sql.append(" AND t.created_at <= :e");
            qp.put("e", dates[1]);
        }
        sql.append(" GROUP BY t.payment_method, t.status_code");
        Query q = entityManager.createNativeQuery(sql.toString());
        qp.forEach(q::setParameter);
        List<Object[]> rows = q.getResultList();

        Map<String, Map<String, Double>> dataMap = new HashMap<>();
        for (Object[] row : rows) {
            String pmCode = (String) row[0];
            String statusCode = (String) row[1];
            double total = ((Number) row[2]).doubleValue();
            dataMap.computeIfAbsent(pmCode, k -> new HashMap<>()).put(statusCode, total);
        }

        List<String> categories = paymentMethods.stream().map(this::capitalize).toList();
        List<Map<String, Object>> series = new ArrayList<>();
        for (String statusCode : statusCodes) {
            List<Number> data = new ArrayList<>();
            for (String pm : paymentMethods) {
                double val = dataMap.getOrDefault(pm, Map.of()).getOrDefault(statusCode, 0.0);
                data.add(val);
            }
            series.add(Map.of("name", StatusUtil.getStatusName(statusCode), "data", data));
        }

        return Map.of("categories", categories, "series", series);
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTimeVolumeChart(Map<String, String> params) {
        LocalDateTime[] dates = parseDates(params);
        List<String> statusCodes = List.of("processing", "completed", "failed", "reversed");

        StringBuilder sql = new StringBuilder(
                "SELECT EXTRACT(HOUR FROM created_at) as h, status_code, COALESCE(SUM(amount), 0) FROM transactions WHERE 1=1");
        Map<String, Object> qp = new HashMap<>();
        if (dates[0] != null) {
            sql.append(" AND created_at >= :s");
            qp.put("s", dates[0]);
        }
        if (dates[1] != null) {
            sql.append(" AND created_at <= :e");
            qp.put("e", dates[1]);
        }
        sql.append(" GROUP BY EXTRACT(HOUR FROM created_at), status_code ORDER BY h");
        Query q = entityManager.createNativeQuery(sql.toString());
        qp.forEach(q::setParameter);
        List<Object[]> rows = q.getResultList();

        Map<Integer, Map<String, Double>> dataMap = new HashMap<>();
        for (Object[] row : rows) {
            int hour = ((Number) row[0]).intValue();
            String status = (String) row[1];
            double total = ((Number) row[2]).doubleValue();
            dataMap.computeIfAbsent(hour, k -> new HashMap<>()).put(status, total);
        }

        List<String> categories = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            categories.add(String.format("%d%s", h == 0 ? 12 : (h > 12 ? h - 12 : h), h < 12 ? "am" : "pm"));
        }

        List<Map<String, Object>> series = new ArrayList<>();
        for (String statusCode : statusCodes) {
            List<Number> data = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                double val = dataMap.getOrDefault(h, Map.of()).getOrDefault(statusCode, 0.0);
                data.add(val);
            }
            series.add(Map.of("name", StatusUtil.getStatusName(statusCode), "data", data));
        }

        return Map.of("categories", categories, "series", series);
    }

    /**
     * Location distribution chart — transaction count and volume grouped by state.
     * Returns top states by transaction count with amount totals per status.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> getLocationDistribution(Map<String, String> params) {
        LocalDateTime[] dates = parseDates(params);

        StringBuilder sql = new StringBuilder(
                "SELECT t.location_state, t.status_code, COUNT(*) as txn_count, COALESCE(SUM(t.amount), 0) as total_amount "
                        + "FROM transactions t " + "WHERE t.location_state IS NOT NULL AND t.location_state != ''");
        Map<String, Object> qp = new HashMap<>();
        if (dates[0] != null) {
            sql.append(" AND t.created_at >= :s");
            qp.put("s", dates[0]);
        }
        if (dates[1] != null) {
            sql.append(" AND t.created_at <= :e");
            qp.put("e", dates[1]);
        }
        sql.append(" GROUP BY t.location_state, t.status_code ORDER BY t.location_state");
        Query q = entityManager.createNativeQuery(sql.toString());
        qp.forEach(q::setParameter);
        List<Object[]> rows = q.getResultList();

        // Build state → status → {count, amount}
        Map<String, Map<String, long[]>> dataMap = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String state = (String) row[0];
            String status = (String) row[1];
            long count = ((Number) row[2]).longValue();
            double amount = ((Number) row[3]).doubleValue();
            dataMap.computeIfAbsent(state, k -> new HashMap<>()).put(status, new long[]{count, (long) amount});
        }

        // Sort by total count descending
        List<String> states = new ArrayList<>(dataMap.keySet());
        states.sort((a, b) -> {
            long totalA = dataMap.get(a).values().stream().mapToLong(v -> v[0]).sum();
            long totalB = dataMap.get(b).values().stream().mapToLong(v -> v[0]).sum();
            return Long.compare(totalB, totalA);
        });

        List<String> categories = states.stream().map(this::capitalize).toList();
        List<String> statusCodes = List.of("processing", "completed", "failed", "reversed");

        // Count series
        List<Map<String, Object>> countSeries = new ArrayList<>();
        for (String statusCode : statusCodes) {
            List<Number> data = new ArrayList<>();
            for (String state : states) {
                long[] vals = dataMap.getOrDefault(state, Map.of()).getOrDefault(statusCode, new long[]{0, 0});
                data.add(vals[0]);
            }
            countSeries.add(Map.of("name", StatusUtil.getStatusName(statusCode), "data", data));
        }

        // Amount series
        List<Map<String, Object>> amountSeries = new ArrayList<>();
        for (String statusCode : statusCodes) {
            List<Number> data = new ArrayList<>();
            for (String state : states) {
                long[] vals = dataMap.getOrDefault(state, Map.of()).getOrDefault(statusCode, new long[]{0, 0});
                data.add(vals[1]);
            }
            amountSeries.add(Map.of("name", StatusUtil.getStatusName(statusCode), "data", data));
        }

        return Map.of("categories", categories, "count_series", countSeries, "amount_series", amountSeries);
    }

    // -- Private helpers --

    @SuppressWarnings("unchecked")
    private List<String> getDistinctChannelCodes() {
        try {
            return entityManager
                    .createNativeQuery(
                            "SELECT DISTINCT channel FROM transactions WHERE channel IS NOT NULL ORDER BY channel")
                    .getResultList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getDistinctPaymentMethodCodes() {
        try {
            return entityManager.createNativeQuery(
                    "SELECT DISTINCT payment_method FROM transactions WHERE payment_method IS NOT NULL ORDER BY payment_method")
                    .getResultList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private TransactionDto mapRow(Object[] r) {
        // Columns: 0=id, 1=reference, 2=amount, 3=status_code, 4=created_at,
        // 5=prod_id, 6=prod_name, 7=prod_code,
        // 8=prov_id, 9=prov_name, 10=prov_code,
        // 11=channel, 12=payment_method,
        // 13=service_fee, 14=agent_commission, 15=aggregator_commission,
        // 16=super_aggregator_commission, 17=company_commission, 18=amount_to_pay,
        // 19=provider_cost, 20=has_reversal,
        // 21=rrn, 22=card_holder, 23=masked_pan, 24=stan, 25=auth_code,
        // 26=terminal_serial
        String prodCode = str(r[7]);

        // For product: if no product record, use product_code as name
        String prodName = str(r[6]);
        if (prodName == null && prodCode != null) {
            prodName = capitalize(prodCode.replace("_", " ").replace("-", " "));
        }

        String statusCode = str(r[3]);
        String channelCode = str(r[11]);
        String pmCode = str(r[12]);

        return TransactionDto.builder().id(lng(r[0])).reference(str(r[1])).product(
                prodCode != null ? NameCodeRef.builder().id(lng(r[5])).name(prodName).code(prodCode).build() : null)
                .provider(ref(r[8], r[9], r[10]))
                .channel(
                        channelCode != null
                                ? NameCodeRef.builder().name(capitalize(channelCode)).code(channelCode).build()
                                : null)
                .paymentMethod(
                        pmCode != null ? NameCodeRef.builder().name(capitalize(pmCode)).code(pmCode).build() : null)
                .amount(r[2] != null ? new BigDecimal(r[2].toString()).toPlainString() : null)
                .status(statusCode != null
                        ? StatusRef.builder().name(StatusUtil.getStatusName(statusCode)).code(statusCode).build()
                        : null)
                .serviceFee(plainAmount(r.length > 13 ? r[13] : null))
                .agentCommission(plainAmount(r.length > 14 ? r[14] : null))
                .aggregatorCommission(plainAmount(r.length > 15 ? r[15] : null))
                .superAggregatorCommission(plainAmount(r.length > 16 ? r[16] : null))
                .companyCommission(plainAmount(r.length > 17 ? r[17] : null))
                .amountToPay(plainAmount(r.length > 18 ? r[18] : null))
                .providerCost(plainAmount(r.length > 19 ? r[19] : null))
                .reversible(r.length > 20 && Boolean.TRUE.equals(r[20]) && "failed".equals(statusCode))
                .rrn(r.length > 21 ? str(r[21]) : null).cardHolder(r.length > 22 ? str(r[22]) : null)
                .maskedPan(r.length > 23 ? maskPan(str(r[23])) : null).stan(r.length > 24 ? str(r[24]) : null)
                .authCode(r.length > 25 ? str(r[25]) : null).terminalSerial(r.length > 26 ? str(r[26]) : null)
                .createdAt(r[4] != null ? toLocalDateTime(r[4]) : null).build();
    }

    /**
     * Render a numeric column as a plain (non-scientific) decimal string, or
     * {@code null} when absent. Used for amounts/commissions in list rows.
     */
    private String plainAmount(Object o) {
        return o != null ? new BigDecimal(o.toString()).toPlainString() : null;
    }

    private NameCodeRef ref(Object id, Object name, Object code) {
        if (id == null && name == null && code == null)
            return null;
        return NameCodeRef.builder().id(lng(id)).name(str(name)).code(str(code)).build();
    }

    private Long lng(Object o) {
        return o != null ? ((Number) o).longValue() : null;
    }

    private String str(Object o) {
        return o != null ? o.toString().trim() : null;
    }

    /**
     * Parse a JSONB column value from a native query. PostgreSQL may return it as a
     * PGobject, String, or Map depending on the driver and Hibernate version.
     */
    private Object parseJsonb(Object o) {
        if (o == null)
            return null;
        if (o instanceof java.util.Map)
            return o;
        String json = o.toString().trim();
        if (json.isEmpty() || "null".equals(json))
            return null;
        return json;
    }

    /**
     * Parse a JSONB column value into a Map. Unlike {@link #parseJsonb} which may
     * return a raw JSON string, this always returns a deserialized Map so the
     * frontend can iterate over the keys (e.g. for transactable fields).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadataJsonb(Object o) {
        if (o == null)
            return null;
        if (o instanceof Map)
            return (Map<String, Object>) o;
        String json = o.toString().trim();
        if (json.isEmpty() || "null".equals(json))
            return null;
        try {
            return JsonParserFactory.getJsonParser().parseMap(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Strip sensitive payment fields (PAN, track2, ICC data, PIN block, etc.) from
     * the raw transaction metadata before exposing it as {@code transactable} to
     * the admin UI. Replaces the full PAN with a masked form for card products.
     *
     * Mirrors {@code TransactionView.sanitizeMetadata} in the transaction
     * microservice. The microservice already sanitizes for the mobile app, but
     * tms-report-java reads {@code transactions.metadata} from the replicated DB
     * directly, so it must apply the same filter independently.
     */
    private Map<String, Object> sanitizeTransactable(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        // Compute masked PAN from the raw value before we strip it. Only set if
        // the metadata didn't already carry a masked_pan from the processor.
        Object rawPan = metadata.get("pan");
        Object existingMasked = metadata.get("masked_pan");

        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (!SENSITIVE_METADATA_KEYS.contains(entry.getKey())) {
                safe.put(entry.getKey(), entry.getValue());
            }
        }

        if (existingMasked == null && rawPan != null) {
            String pan = rawPan.toString();
            if (pan.length() >= 4) {
                safe.put("masked_pan", "**** **** **** " + pan.substring(pan.length() - 4));
            }
        }
        return safe;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    /** True when a request param is present and non-blank. */
    private boolean isPresent(String val) {
        return val != null && !val.isBlank();
    }

    private void addFilter(StringBuilder where, Map<String, Object> qParams, Map<String, String> params, String key,
            String column) {
        String val = params.get(key);
        if (val != null && !val.isBlank()) {
            // Support comma-separated values for IN clause
            if (val.contains(",")) {
                String paramName = key.replace(".", "_") + "_list";
                List<String> values = java.util.Arrays.stream(val.split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).toList();
                where.append(" AND ").append(column).append(" IN (:").append(paramName).append(")");
                qParams.put(paramName, values);
            } else {
                where.append(" AND ").append(column).append(" = :").append(key.replace(".", "_"));
                try {
                    qParams.put(key.replace(".", "_"), Long.parseLong(val));
                } catch (NumberFormatException e) {
                    qParams.put(key.replace(".", "_"), val);
                }
            }
        }
    }

    /**
     * Product filter that expands a single id (or code) into every alias known to
     * refer to the same logical product, then matches against
     * {@code (id_column IN (...) OR code_column IN (...))}.
     *
     * <p>
     * Supports comma-separated product codes for filtering by multiple products.
     *
     * <p>
     * Production was migrated from the legacy camelCase monolith schema. The
     * config-service seeder marks the camelCase rows {@code status='legacy'} but
     * they remain in the {@code products} table because historical
     * {@code transactions.product_id} rows still reference them. The admin filter
     * dropdown only ships active products, so equality on {@code product_id} alone
     * hides every pre-migration transaction.
     *
     * <p>
     * Resolving via the {@code products} table looks up the canonical row for the
     * incoming id, then collects every product whose code is an alias (kebab-case
     * canonical + camelCase legacy + the {@code completion}/ {@code complete}
     * pair). The resulting clause stays index-friendly on both columns.
     */
    private void addProductFilter(StringBuilder where, Map<String, Object> qParams, Map<String, String> params,
            String key, String idColumn, String codeColumn) {
        String val = params.get(key);
        if (val == null || val.isBlank()) {
            return;
        }

        // Support comma-separated product codes
        Set<String> allCodeAliases = new java.util.LinkedHashSet<>();
        String[] values = val.split(",");
        for (String v : values) {
            v = v.trim();
            if (v.isEmpty()) {
                continue;
            }
            Set<String> aliases = resolveProductCodeAliases(v);
            allCodeAliases.addAll(aliases);
        }

        if (allCodeAliases.isEmpty()) {
            // Couldn't resolve to any known product — fall back to the original
            // exact match so the caller still sees a result set, even if empty.
            addFilter(where, qParams, params, key, idColumn);
            return;
        }

        Set<Long> idAliases = new java.util.LinkedHashSet<>();
        try {
            @SuppressWarnings("unchecked")
            List<Object> rows = entityManager.createNativeQuery("SELECT id FROM products WHERE code IN (:codes)")
                    .setParameter("codes", allCodeAliases).getResultList();
            for (Object row : rows) {
                if (row instanceof Number n) {
                    idAliases.add(n.longValue());
                }
            }
        } catch (Exception e) {
            // Best effort — fall through with whatever ids we have.
        }

        String idsParam = key.replace(".", "_") + "_ids";
        String codesParam = key.replace(".", "_") + "_codes";
        if (!idAliases.isEmpty()) {
            where.append(" AND (").append(idColumn).append(" IN (:").append(idsParam).append(")").append(" OR ")
                    .append(codeColumn).append(" IN (:").append(codesParam).append("))");
            qParams.put(idsParam, idAliases);
            qParams.put(codesParam, allCodeAliases);
        } else {
            where.append(" AND ").append(codeColumn).append(" IN (:").append(codesParam).append(")");
            qParams.put(codesParam, allCodeAliases);
        }
    }

    /**
     * Resolve a product filter value (id, code, or legacy code) into the set of
     * every {@code products.code} that refers to the same logical product.
     *
     * <p>
     * The set always contains the resolved canonical code plus any known camelCase
     * or {@code completion}/{@code complete} variant. Pure-string inputs (the
     * dropdown sends ids, but a deeplink could send a code) are normalised the same
     * way.
     */
    @SuppressWarnings("unchecked")
    private Set<String> resolveProductCodeAliases(String value) {
        String canonical = null;
        try {
            Long id = Long.parseLong(value);
            List<Object> rows = entityManager.createNativeQuery("SELECT code FROM products WHERE id = :id")
                    .setParameter("id", id).getResultList();
            if (!rows.isEmpty() && rows.get(0) != null) {
                canonical = rows.get(0).toString();
            }
        } catch (NumberFormatException ignored) {
            canonical = value;
        } catch (Exception e) {
            // Lookup failed — fall back to treating the input as a code.
            canonical = null;
        }

        if (canonical == null || canonical.isBlank()) {
            return Set.of();
        }

        Set<String> aliases = new java.util.LinkedHashSet<>();
        aliases.add(canonical);

        // Map every known kebab/camel pair both ways. Adding a new product
        // here keeps the legacy-row backfill working without a schema change.
        Map<String, String> kebabToCamel = Map.of("bank-transfer", "bankTransfer", "cash-advance", "cashAdvance",
                "commission-transfer", "commissionTransfer", "local-transfer", "localTransfer", "virtual-funding",
                "virtualFunding", "completion", "complete");
        if (kebabToCamel.containsKey(canonical)) {
            aliases.add(kebabToCamel.get(canonical));
        } else {
            String resolved = canonical;
            kebabToCamel.forEach((kebab, camel) -> {
                if (camel.equals(resolved)) {
                    aliases.add(kebab);
                }
            });
        }
        return aliases;
    }

    private LocalDateTime[] parseDates(Map<String, String> params) {
        return QueryFilterHelper.extractDates(params);
    }
}

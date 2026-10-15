package com.tms.report.modules.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tms.report.modules.audit.model.SettlementAccountInfo;
import com.tms.report.modules.grpc.service.GrpcClient;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Service for resolving entity IDs to human-readable names for audit logging.
 * Request-scoped to provide caching within a single HTTP request lifecycle.
 *
 * <p>
 * This service supports the Smart Audit Log feature by providing lookup methods
 * for various entity types (branches, TIDs, terminals, settlement accounts).
 * Results are cached within the request scope to avoid duplicate database
 * queries, and lookups are limited to a maximum of 3 per request for
 * performance.
 *
 * <p>
 * Cache key format: "{entity_type}:{id}" or "{entity_type}:{id}:{merchant_id}"
 *
 * @see com.tms.report.modules.audit.filter.AuditLoggingFilter
 */
@Slf4j
@Service
@RequestScope
public class AuditEntityLookupService {

    /** Maximum number of entity lookups allowed per request */
    private static final int MAX_LOOKUPS_PER_REQUEST = 3;

    /** ObjectMapper for JSON parsing */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Cache key prefixes for different entity types */
    private static final String CACHE_KEY_BRANCH = "branch";
    private static final String CACHE_KEY_TID = "tid";
    private static final String CACHE_KEY_TERMINAL = "terminal";
    private static final String CACHE_KEY_SETTLEMENT_ACCOUNT = "settlement_account";
    private static final String CACHE_KEY_DEFAULT_SETTLEMENT = "default_settlement";

    /** Request-scoped cache for entity lookups */
    private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();

    /**
     * Counter for lookups in current request, limited to MAX_LOOKUPS_PER_REQUEST
     */
    private final AtomicInteger lookupCount = new AtomicInteger(0);

    private final EntityManager entityManager;
    private final GrpcClient grpcClient;

    public AuditEntityLookupService(EntityManager entityManager, GrpcClient grpcClient) {
        this.entityManager = entityManager;
        this.grpcClient = grpcClient;
    }

    /**
     * Retrieve branch name by ID and merchant ID.
     *
     * <p>
     * Queries tms-config via gRPC to resolve the branch name. Results are cached
     * within the request scope.
     *
     * @param branchId
     *            the branch ID to look up
     * @param merchantId
     *            the merchant ID (user_id) that owns the branch
     * @return branch name or empty if not found
     */
    public Optional<String> getBranchName(Long branchId, Long merchantId) {
        if (branchId == null) {
            return Optional.empty();
        }

        String cacheKey = buildCacheKey(CACHE_KEY_BRANCH, branchId, merchantId);
        return getCachedOrLookup(cacheKey, () -> lookupBranchName(branchId, merchantId));
    }

    /**
     * Retrieve TID value (terminal_id field) by TID ID.
     *
     * <p>
     * Queries the local tids table to get the terminal_id value. Note that
     * terminal_id in the tids table is the actual TID string value, not the
     * terminal's database ID.
     *
     * @param tidId
     *            the TID record ID (primary key)
     * @return terminal_id value or empty if not found
     */
    public Optional<String> getTidValue(Long tidId) {
        if (tidId == null) {
            return Optional.empty();
        }

        String cacheKey = buildCacheKey(CACHE_KEY_TID, tidId, null);
        return getCachedOrLookup(cacheKey, () -> lookupTidValue(tidId));
    }

    /**
     * Retrieve terminal serial number by terminal ID.
     *
     * <p>
     * Queries the local terminals table to get the serial number.
     *
     * @param terminalId
     *            the terminal ID (primary key)
     * @return serial number or empty if not found
     */
    public Optional<String> getTerminalSerial(Long terminalId) {
        if (terminalId == null) {
            return Optional.empty();
        }

        String cacheKey = buildCacheKey(CACHE_KEY_TERMINAL, terminalId, null);
        return getCachedOrLookup(cacheKey, () -> lookupTerminalSerial(terminalId));
    }

    /**
     * Retrieve settlement account details by account ID.
     *
     * <p>
     * Queries tms-config via gRPC to get the settlement account information
     * including bank name, account name, and masked account number.
     *
     * @param accountId
     *            the settlement account ID
     * @param merchantId
     *            the merchant ID that owns the account
     * @return settlement account info or empty if not found
     */
    public Optional<SettlementAccountInfo> getSettlementAccount(Long accountId, Long merchantId) {
        if (accountId == null) {
            return Optional.empty();
        }

        String cacheKey = buildCacheKey(CACHE_KEY_SETTLEMENT_ACCOUNT, accountId, merchantId);
        return getCachedOrLookup(cacheKey, () -> lookupSettlementAccount(accountId, merchantId));
    }

    /**
     * Retrieve the default settlement account for a merchant.
     *
     * <p>
     * Queries tms-config via gRPC to find the merchant's default settlement account
     * (the one marked as is_default=true).
     *
     * @param merchantId
     *            the merchant ID (user_id)
     * @return default settlement account info or empty if no default exists
     */
    public Optional<SettlementAccountInfo> getDefaultSettlementAccount(Long merchantId) {
        if (merchantId == null) {
            return Optional.empty();
        }

        String cacheKey = buildCacheKey(CACHE_KEY_DEFAULT_SETTLEMENT, merchantId, null);
        return getCachedOrLookup(cacheKey, () -> lookupDefaultSettlementAccount(merchantId));
    }

    /**
     * Extract settlement account details from JSON request body.
     *
     * <p>
     * Parses the JSON body to extract bank_name, account_number, and account_name
     * fields. Handles both snake_case and camelCase field names. The account number
     * is automatically masked to show only the last 4 digits.
     *
     * @param jsonBody
     *            the JSON request body string
     * @return settlement account info extracted from JSON or empty if parsing fails
     */
    public Optional<SettlementAccountInfo> extractSettlementAccountFromJson(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return Optional.empty();
        }

        // Note: This does not count toward lookup limit as it's a parse operation, not
        // a DB/gRPC call
        return parseSettlementAccountFromJson(jsonBody);
    }

    // ═══════════════════════════════════════════
    // Cache and lookup management
    // ═══════════════════════════════════════════

    /**
     * Build a cache key from entity type and IDs.
     *
     * @param entityType
     *            the entity type prefix
     * @param id
     *            the primary entity ID
     * @param secondaryId
     *            optional secondary ID (e.g., merchant ID)
     * @return cache key in format "type:id" or "type:id:secondaryId"
     */
    private String buildCacheKey(String entityType, Long id, Long secondaryId) {
        if (secondaryId != null) {
            return entityType + ":" + id + ":" + secondaryId;
        }
        return entityType + ":" + id;
    }

    /**
     * Get a cached value or perform lookup if not cached.
     *
     * <p>
     * Respects the MAX_LOOKUPS_PER_REQUEST limit. If the limit is reached, returns
     * empty without performing the lookup.
     *
     * @param cacheKey
     *            the cache key
     * @param lookupSupplier
     *            supplier that performs the actual lookup
     * @return the cached or looked-up value
     */
    @SuppressWarnings("unchecked")
    private <T> Optional<T> getCachedOrLookup(String cacheKey,
            java.util.function.Supplier<Optional<T>> lookupSupplier) {
        // Check cache first
        Object cached = cache.get(cacheKey);
        if (cached != null) {
            if (cached == NullMarker.INSTANCE) {
                return Optional.empty();
            }
            return Optional.of((T) cached);
        }

        // Check lookup limit before performing lookup
        if (lookupCount.get() >= MAX_LOOKUPS_PER_REQUEST) {
            log.debug("Lookup limit reached ({}/{}), skipping lookup for key: {}", lookupCount.get(),
                    MAX_LOOKUPS_PER_REQUEST, cacheKey);
            return Optional.empty();
        }

        // Perform lookup
        lookupCount.incrementAndGet();
        try {
            Optional<T> result = lookupSupplier.get();

            // Cache the result (use NullMarker for empty results to distinguish from "not
            // cached")
            if (result.isPresent()) {
                cache.put(cacheKey, result.get());
            } else {
                cache.put(cacheKey, NullMarker.INSTANCE);
            }

            return result;
        } catch (Exception e) {
            log.warn("Lookup failed for cache key {}: {}", cacheKey, e.getMessage());
            cache.put(cacheKey, NullMarker.INSTANCE);
            return Optional.empty();
        }
    }

    /** Marker object for caching null/empty results */
    private enum NullMarker {
        INSTANCE
    }

    // ═══════════════════════════════════════════
    // Actual lookup implementations (stubbed for now)
    // ═══════════════════════════════════════════

    /**
     * Lookup branch name from tms-config via gRPC.
     *
     * @param branchId
     *            the branch ID to look up
     * @param merchantId
     *            the merchant ID (user_id) that owns the branch
     * @return the branch name or empty if not found or on error
     */
    private Optional<String> lookupBranchName(Long branchId, Long merchantId) {
        try {
            Map<String, Object> branch = grpcClient.getMerchantBranch(branchId, merchantId);
            if (branch == null) {
                log.debug("Branch not found for branchId={}, merchantId={}", branchId, merchantId);
                return Optional.empty();
            }

            Object name = branch.get("name");
            if (name == null) {
                log.debug("Branch found but name is null for branchId={}", branchId);
                return Optional.empty();
            }

            return Optional.of(name.toString());
        } catch (Exception e) {
            log.warn("Failed to lookup branch name for branchId={}, merchantId={}: {}", branchId, merchantId,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Lookup TID value from local database.
     *
     * <p>
     * Queries the tids table to retrieve the terminal_id field value for a given
     * TID record ID. The terminal_id field contains the actual TID string value
     * (e.g., "2001234") used for transaction processing.
     *
     * @param tidId
     *            the TID record primary key
     * @return terminal_id value or empty if not found
     */
    private Optional<String> lookupTidValue(Long tidId) {
        String sql = "SELECT terminal_id FROM tids WHERE id = :tidId";

        try {
            Object result = entityManager.createNativeQuery(sql).setParameter("tidId", tidId).getSingleResult();

            if (result != null) {
                String terminalId = result.toString();
                log.debug("Resolved TID value for id={}: {}", tidId, terminalId);
                return Optional.of(terminalId);
            }
            return Optional.empty();
        } catch (jakarta.persistence.NoResultException e) {
            log.debug("TID not found for id={}", tidId);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to lookup TID value for id={}: {}", tidId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Lookup terminal serial from local database.
     *
     * @param terminalId
     *            the terminal ID (primary key)
     * @return terminal serial or empty if not found or on error
     */
    private Optional<String> lookupTerminalSerial(Long terminalId) {
        try {
            String serial = (String) entityManager
                    .createNativeQuery("SELECT serial FROM terminals WHERE id = :terminalId")
                    .setParameter("terminalId", terminalId).getSingleResult();
            return Optional.ofNullable(serial);
        } catch (jakarta.persistence.NoResultException e) {
            log.debug("Terminal not found for id={}", terminalId);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to lookup terminal serial for id={}: {}", terminalId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Lookup settlement account details from tms-config via gRPC.
     *
     * <p>
     * Fetches all merchant settlement accounts via gRPC and finds the one matching
     * the given accountId. Extracts bank_code, account_number, and account_name
     * from the result and converts bank_code to bank_name.
     *
     * @param accountId
     *            the settlement account ID to look up
     * @param merchantId
     *            the merchant ID that owns the account
     * @return settlement account info or empty if not found
     */
    private Optional<SettlementAccountInfo> lookupSettlementAccount(Long accountId, Long merchantId) {
        try {
            List<Map<String, Object>> accounts = grpcClient.listMerchantSettlementAccounts(merchantId);
            if (accounts == null || accounts.isEmpty()) {
                log.debug("No settlement accounts found for merchantId={}", merchantId);
                return Optional.empty();
            }

            // Find the account matching the given accountId
            Optional<Map<String, Object>> matchingAccount = accounts.stream()
                    .filter(acct -> accountId.equals(toLong(acct.get("id")))).findFirst();

            if (matchingAccount.isEmpty()) {
                log.debug("Settlement account not found for accountId={}, merchantId={}", accountId, merchantId);
                return Optional.empty();
            }

            return convertToSettlementAccountInfo(matchingAccount.get());
        } catch (Exception e) {
            log.warn("Failed to lookup settlement account for accountId={}, merchantId={}: {}", accountId, merchantId,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Lookup default settlement account for a merchant from tms-config via gRPC.
     *
     * <p>
     * Fetches all merchant settlement accounts via gRPC and finds the one marked as
     * is_default=true. If no default account exists, falls back to the first
     * account in the list.
     *
     * @param merchantId
     *            the merchant ID to look up default account for
     * @return default settlement account info or empty if no accounts exist
     */
    private Optional<SettlementAccountInfo> lookupDefaultSettlementAccount(Long merchantId) {
        try {
            List<Map<String, Object>> accounts = grpcClient.listMerchantSettlementAccounts(merchantId);
            if (accounts == null || accounts.isEmpty()) {
                log.debug("No settlement accounts found for merchantId={}", merchantId);
                return Optional.empty();
            }

            // Find account with is_default=true, fallback to first account
            Map<String, Object> defaultAccount = accounts.stream()
                    .filter(acct -> Boolean.TRUE.equals(acct.get("is_default"))).findFirst().orElse(accounts.get(0)); // Fall
                                                                                                                      // back
                                                                                                                      // to
                                                                                                                      // first
                                                                                                                      // if
                                                                                                                      // no
                                                                                                                      // default

            return convertToSettlementAccountInfo(defaultAccount);
        } catch (Exception e) {
            log.warn("Failed to lookup default settlement account for merchantId={}: {}", merchantId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Convert a settlement account map from gRPC response to SettlementAccountInfo.
     *
     * @param account
     *            the account data map from gRPC
     * @return SettlementAccountInfo or empty if required fields are missing
     */
    private Optional<SettlementAccountInfo> convertToSettlementAccountInfo(Map<String, Object> account) {
        if (account == null) {
            return Optional.empty();
        }

        String bankCode = toString(account.get("bank_code"));
        String accountNumber = toString(account.get("account_number"));
        String accountName = toString(account.get("account_name"));

        if (accountNumber == null) {
            log.debug("Settlement account missing account_number field");
            return Optional.empty();
        }

        String bankName = bankName(bankCode);
        return Optional.of(SettlementAccountInfo.fromAccountNumber(bankName, accountName, accountNumber));
    }

    /**
     * Safely convert object to Long.
     */
    private static Long toLong(Object o) {
        if (o == null)
            return null;
        if (o instanceof Number)
            return ((Number) o).longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Safely convert object to String.
     */
    private static String toString(Object o) {
        return o != null ? o.toString() : null;
    }

    // ── Bank code to name mapping ──────────────────────────────────────────────

    private static final Map<String, String> BANK_NAMES = Map.ofEntries(Map.entry("044", "Access Bank"),
            Map.entry("023", "Citibank"), Map.entry("050", "Ecobank"), Map.entry("084", "Enterprise Bank"),
            Map.entry("070", "Fidelity Bank"), Map.entry("011", "First Bank"), Map.entry("214", "FCMB"),
            Map.entry("058", "GTBank"), Map.entry("030", "Heritage Bank"), Map.entry("301", "Jaiz Bank"),
            Map.entry("082", "Keystone Bank"), Map.entry("526", "Parallex Bank"), Map.entry("076", "Polaris Bank"),
            Map.entry("101", "Providus Bank"), Map.entry("221", "Stanbic IBTC"), Map.entry("068", "Standard Chartered"),
            Map.entry("232", "Sterling Bank"), Map.entry("100", "Suntrust Bank"), Map.entry("032", "Union Bank"),
            Map.entry("033", "United Bank for Africa"), Map.entry("215", "Unity Bank"), Map.entry("035", "Wema Bank"),
            Map.entry("057", "Zenith Bank"), Map.entry("103", "Globus Bank"), Map.entry("102", "Titan Trust Bank"),
            Map.entry("120001", "9PSB"));

    /**
     * Convert bank code to bank name using the BANK_NAMES map.
     *
     * @param code
     *            the bank code
     * @return the bank name, or the code itself if not found in the map
     */
    private static String bankName(String code) {
        return code != null ? BANK_NAMES.getOrDefault(code, code) : null;
    }

    /**
     * Parse settlement account details from JSON body.
     *
     * <p>
     * Extracts bank name, account number, and account name from the JSON body.
     * Handles both snake_case field names (bank_name, account_number, account_name)
     * and camelCase field names (bankName, accountNumber, accountName).
     *
     * @param jsonBody
     *            the JSON request body to parse
     * @return settlement account info or empty if parsing fails or required fields
     *         are missing
     */
    private Optional<SettlementAccountInfo> parseSettlementAccountFromJson(String jsonBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonBody);

            // Extract fields, checking both snake_case and camelCase
            String bankName = getJsonField(root, "bank_name", "bankName");
            String accountNumber = getJsonField(root, "account_number", "accountNumber");
            String accountName = getJsonField(root, "account_name", "accountName");

            // Validate required fields
            if (bankName == null || accountNumber == null || accountName == null) {
                log.debug(
                        "Missing required settlement account fields in JSON body. bankName={}, accountNumber={}, accountName={}",
                        bankName != null, accountNumber != null, accountName != null);
                return Optional.empty();
            }

            return Optional.of(SettlementAccountInfo.fromAccountNumber(bankName, accountName, accountNumber));
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse settlement account from JSON body: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extract a field value from a JSON node, checking snake_case first then
     * camelCase.
     *
     * @param node
     *            the JSON node to extract from
     * @param snakeCaseName
     *            the snake_case field name to check first
     * @param camelCaseName
     *            the camelCase field name to check second
     * @return the field value or null if not found
     */
    private String getJsonField(JsonNode node, String snakeCaseName, String camelCaseName) {
        JsonNode field = node.get(snakeCaseName);
        if (field == null || field.isNull()) {
            field = node.get(camelCaseName);
        }
        return (field != null && !field.isNull()) ? field.asText() : null;
    }
}

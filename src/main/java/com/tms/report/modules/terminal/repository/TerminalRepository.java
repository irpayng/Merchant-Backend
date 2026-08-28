package com.tms.report.modules.terminal.repository;

import com.tms.report.modules.terminal.model.Terminal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TerminalRepository extends JpaRepository<Terminal, Long>, JpaSpecificationExecutor<Terminal> {

    @Query("SELECT DISTINCT t.make FROM Terminal t WHERE t.make IS NOT NULL ORDER BY t.make")
    List<String> findDistinctMakes();

    @Query("SELECT DISTINCT t.model FROM Terminal t WHERE t.model IS NOT NULL ORDER BY t.model")
    List<String> findDistinctModels();

    @Query("SELECT DISTINCT t.os FROM Terminal t WHERE t.os IS NOT NULL ORDER BY t.os")
    List<String> findDistinctOs();

    /**
     * Resolve the agent currently bound to a device serial. {@code terminals} is
     * the canonical source of "who owns this device" — it's set when the agent runs
     * the prep flow on the POS and updated on every re-prep — so it's preferred
     * over derived signals like {@code pos_leases.user_id}.
     */
    java.util.Optional<Terminal> findBySerial(String serial);

    /**
     * Paged terminal list with optional free-text search and metric filters.
     *
     * <p>
     * The metric filters use a LATERAL join to the most recent
     * {@code terminal_metrics} row per serial, which uses the existing
     * {@code (serial, created_at DESC)} index. All filter parameters can be passed
     * as {@code null} to disable that filter — Spring binds the param with
     * {@code IS NOT NULL} guards in the WHERE clause.
     *
     * @param search
     *            lowercased substring matched against serial, make, or model
     * @param make
     *            exact terminal make match
     * @param os
     *            exact terminal os match
     * @param networkType
     *            "wifi" / "cellular" / "ethernet" / "none"
     * @param batteryBelow
     *            upper bound (exclusive) for the latest battery_pct
     * @param printerStatus
     *            exact latest printer status (0 = ready)
     * @param staleSince
     *            when set, returns only terminals whose latest metric is older than
     *            this timestamp (i.e. not reported recently)
     * @param mapped
     *            {@code "true"} returns only terminals bound to a user
     *            ({@code user_id IS NOT NULL}), {@code "false"} only unmapped
     *            terminals; {@code null} disables the filter
     */
    @Query(value = """
            SELECT t.* FROM terminals t
            LEFT JOIN LATERAL (
              SELECT *
              FROM terminal_metrics m
              WHERE m.serial = t.serial
              ORDER BY m.created_at DESC
              LIMIT 1
            ) m ON true
            WHERE
              (CAST(:search AS text) IS NULL OR
                LOWER(t.serial) LIKE CAST(:search AS text) OR
                LOWER(COALESCE(t.make, '')) LIKE CAST(:search AS text) OR
                LOWER(COALESCE(t.model, '')) LIKE CAST(:search AS text))
              AND (CAST(:make AS text) IS NULL OR UPPER(t.make) = UPPER(CAST(:make AS text)))
              AND (CAST(:os AS text) IS NULL OR t.os = CAST(:os AS text))
              AND (CAST(:networkType AS text) IS NULL OR m.network_type = CAST(:networkType AS text))
              AND (CAST(:batteryBelow AS integer) IS NULL OR m.battery_pct < CAST(:batteryBelow AS integer))
              AND (CAST(:printerStatus AS integer) IS NULL OR m.printer_status = CAST(:printerStatus AS integer))
              AND (CAST(:staleSince AS timestamptz) IS NULL OR m.created_at < CAST(:staleSince AS timestamptz) OR m.created_at IS NULL)
              AND (CAST(:mapped AS text) IS NULL
                OR (CAST(:mapped AS text) = 'true' AND t.user_id IS NOT NULL)
                OR (CAST(:mapped AS text) = 'false' AND t.user_id IS NULL))
              AND (CAST(:locked AS text) IS NULL
                OR (CAST(:locked AS text) = 'true' AND t.locked IS TRUE)
                OR (CAST(:locked AS text) = 'false' AND (t.locked IS FALSE OR t.locked IS NULL)))
              AND (CAST(:merchantId AS bigint) IS NULL OR t.user_id = CAST(:merchantId AS bigint))
              AND (CAST(:terminalId AS bigint) IS NULL OR t.id = CAST(:terminalId AS bigint))
              AND (CAST(:dateFrom AS timestamptz) IS NULL OR t.created_at >= CAST(:dateFrom AS timestamptz))
              AND (CAST(:dateTo AS timestamptz) IS NULL OR t.created_at <= CAST(:dateTo AS timestamptz))
            ORDER BY t.created_at DESC
            """, countQuery = """
            SELECT COUNT(*) FROM terminals t
            LEFT JOIN LATERAL (
              SELECT *
              FROM terminal_metrics m
              WHERE m.serial = t.serial
              ORDER BY m.created_at DESC
              LIMIT 1
            ) m ON true
            WHERE
              (CAST(:search AS text) IS NULL OR
                LOWER(t.serial) LIKE CAST(:search AS text) OR
                LOWER(COALESCE(t.make, '')) LIKE CAST(:search AS text) OR
                LOWER(COALESCE(t.model, '')) LIKE CAST(:search AS text))
              AND (CAST(:make AS text) IS NULL OR UPPER(t.make) = UPPER(CAST(:make AS text)))
              AND (CAST(:os AS text) IS NULL OR t.os = CAST(:os AS text))
              AND (CAST(:networkType AS text) IS NULL OR m.network_type = CAST(:networkType AS text))
              AND (CAST(:batteryBelow AS integer) IS NULL OR m.battery_pct < CAST(:batteryBelow AS integer))
              AND (CAST(:printerStatus AS integer) IS NULL OR m.printer_status = CAST(:printerStatus AS integer))
              AND (CAST(:staleSince AS timestamptz) IS NULL OR m.created_at < CAST(:staleSince AS timestamptz) OR m.created_at IS NULL)
              AND (CAST(:mapped AS text) IS NULL
                OR (CAST(:mapped AS text) = 'true' AND t.user_id IS NOT NULL)
                OR (CAST(:mapped AS text) = 'false' AND t.user_id IS NULL))
              AND (CAST(:locked AS text) IS NULL
                OR (CAST(:locked AS text) = 'true' AND t.locked IS TRUE)
                OR (CAST(:locked AS text) = 'false' AND (t.locked IS FALSE OR t.locked IS NULL)))
              AND (CAST(:merchantId AS bigint) IS NULL OR t.user_id = CAST(:merchantId AS bigint))
              AND (CAST(:terminalId AS bigint) IS NULL OR t.id = CAST(:terminalId AS bigint))
              AND (CAST(:dateFrom AS timestamptz) IS NULL OR t.created_at >= CAST(:dateFrom AS timestamptz))
              AND (CAST(:dateTo AS timestamptz) IS NULL OR t.created_at <= CAST(:dateTo AS timestamptz))
            """, nativeQuery = true)
    Page<Terminal> findFiltered(@Param("search") String search, @Param("make") String make, @Param("os") String os,
            @Param("networkType") String networkType, @Param("batteryBelow") Integer batteryBelow,
            @Param("printerStatus") Integer printerStatus, @Param("staleSince") LocalDateTime staleSince,
            @Param("mapped") String mapped, @Param("locked") String locked, @Param("merchantId") Long merchantId,
            @Param("terminalId") Long terminalId, @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo, Pageable pageable);

    /**
     * All terminals tied to a user. Used by the user-detail page on tms-ui to
     * render the per-user POS devices tab. Ordered by latest first so the most
     * recently provisioned device shows on top.
     */
    @Query("SELECT t FROM Terminal t WHERE t.userId = :userId ORDER BY t.createdAt DESC")
    List<Terminal> findByUserId(@Param("userId") Long userId);

    /**
     * Resolve the display name and email for a set of user ids in a single round
     * trip, so the terminal list/detail can show <em>who</em> a device is mapped to
     * without an N+1 lookup. The name is the {@code profiles} full name, falling
     * back to the email when the profile is missing or blank — mirroring
     * {@code User.getName()}.
     *
     * <p>
     * Returns rows of {@code [user_id (Long), name (String), email (String)]}.
     * Users without a profile still return a row (LEFT JOIN), with {@code name}
     * coalesced to the email.
     */
    @Query(value = """
            SELECT u.id AS user_id,
                   COALESCE(NULLIF(TRIM(COALESCE(p.first_name, '') || ' ' || COALESCE(p.last_name, '')), ''), u.email) AS name,
                   u.email AS email
            FROM users u
            LEFT JOIN profiles p ON p.user_id = u.id
            WHERE u.id IN (:userIds)
            """, nativeQuery = true)
    List<Object[]> findUserSummaries(@Param("userIds") List<Long> userIds);
}

package com.tms.report.core.util;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Shared {@link LocalDateTime} coercion for results returned by native JDBC
 * queries. PostgreSQL {@code timestamp with time zone} columns can arrive as
 * {@link OffsetDateTime}, {@link Instant}, {@link Timestamp}, or
 * {@link LocalDateTime} depending on driver version, dialect settings, and
 * whether Hibernate is in the call path. Handling only one or two of these
 * silently drops the column for everyone else (the {@code Submission Date}
 * regression in the CAC verification view is a recent example).
 *
 * <p>
 * Use {@link #toLocalDateTime(Object)} as the single conversion point in
 * native-query result mapping — never inline {@code instanceof} cascades that
 * miss a type.
 */
public final class Dates {

    private Dates() {
    }

    /**
     * Convert any of the JDBC/JPA temporal representations to a
     * {@link LocalDateTime} in the system zone. Returns {@code null} for
     * {@code null} input. Falls back to {@link Object#toString()} parsing for
     * unknown types so a string-typed driver result still produces a value.
     */
    public static LocalDateTime toLocalDateTime(Object o) {
        if (o == null)
            return null;
        if (o instanceof Timestamp ts)
            return ts.toLocalDateTime();
        if (o instanceof LocalDateTime ldt)
            return ldt;
        if (o instanceof Instant inst)
            return LocalDateTime.ofInstant(inst, ZoneId.systemDefault());
        if (o instanceof OffsetDateTime odt)
            return odt.toLocalDateTime();
        try {
            String s = o.toString();
            return LocalDateTime.parse(s.replace(" ", "T").substring(0, Math.min(19, s.length())));
        } catch (Exception ignored) {
            return null;
        }
    }
}

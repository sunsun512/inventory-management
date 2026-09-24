package com.example.inventory.management.inventory.common.exception;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Extracts the Postgres SQLState from a (Spring/Hibernate-wrapped) exception chain, so callers
 * can tell apart failures that Spring translates into the same exception type — e.g. both a
 * unique violation (23505) and "bigint out of range" (22003) surface as
 * DataIntegrityViolationException.
 */
public final class SqlStates {

    public static final String UNIQUE_VIOLATION = "23505";
    public static final String NUMERIC_VALUE_OUT_OF_RANGE = "22003";

    private SqlStates() {
    }

    public static Optional<String> of(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return Optional.of(sqlException.getSQLState());
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return Optional.empty();
    }

    public static boolean is(Throwable throwable, String sqlState) {
        return of(throwable).map(sqlState::equals).orElse(false);
    }
}

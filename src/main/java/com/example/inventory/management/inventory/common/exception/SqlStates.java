package com.example.inventory.management.inventory.common.exception;

import org.hibernate.exception.ConstraintViolationException;

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

    /**
     * True if the failure is a unique violation (23505) of the named constraint. The name comes
     * from Hibernate's ConstraintViolationException when present, otherwise from the driver's
     * message ({@code violates unique constraint "<name>"}), e.g. for plain JDBC access.
     */
    public static boolean isUniqueViolationOf(Throwable throwable, String constraintName) {
        if (!is(throwable, UNIQUE_VIOLATION)) {
            return false;
        }
        String quotedName = "\"" + constraintName + "\"";
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            if (current instanceof SQLException && current.getMessage() != null
                    && current.getMessage().contains(quotedName)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }
}

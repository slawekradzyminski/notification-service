package com.awesome.notification.events;

import com.awesome.notification.client.BackendException;
import com.awesome.notification.controller.exception.CustomException;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionException;
import tools.jackson.core.JacksonException;

import java.sql.SQLException;
import java.time.DateTimeException;

/**
 * Tells a poison event (one whose own content can never be handled) from a failure of the
 * infrastructure around it, and from an unexpected failure. Only a poison event may be skipped by
 * the poller; anything else stops the run with the cursor unchanged, so the next run retries it.
 *
 * <p>Poison: {@link IllegalArgumentException} (malformed data, unknown template, unparsable id or
 * number), a {@link DataIntegrityViolationException} (a constraint the event's own data breaks,
 * {@code DuplicateKeyException} included), a Jackson parse or mapping failure, an unparsable
 * date, a backend 4xx about the event ({@link BackendException} that is not transient) and a
 * {@link CustomException} with a 4xx status (for example a missing template property). A failure with a {@link DataAccessException},
 * {@link TransactionException}, JPA {@link PersistenceException} or {@link SQLException} anywhere
 * in its cause chain (a dropped connection, a pool or query timeout, a failed commit) is an
 * infrastructure failure, whatever wraps it. Anything else (a {@code NullPointerException}, an
 * {@code IllegalStateException}) is unexpected: it also keeps the cursor, but is logged at ERROR
 * with its stack trace because it points at a bug.
 */
final class EventFailures {

    private static final int MAX_DEPTH = 16;

    private EventFailures() {
    }

    /** How the poller treats a failure. */
    enum Kind {
        /** The event's own content cannot be handled: skip it. */
        POISON,
        /** Database, transaction or backend failure: keep the cursor and retry on the next run. */
        INFRASTRUCTURE,
        /** Neither (most likely a bug): keep the cursor and retry, logged with its stack trace. */
        UNEXPECTED
    }

    static Kind classify(Throwable failure) {
        if (isPoison(failure)) {
            return Kind.POISON;
        }
        if (involvesInfrastructure(failure) || isTransientBackendFailure(failure)) {
            return Kind.INFRASTRUCTURE;
        }
        return Kind.UNEXPECTED;
    }

    private static boolean isPoison(Throwable failure) {
        if (involves(failure, DataIntegrityViolationException.class)) {
            return true;
        }
        if (involvesInfrastructure(failure)) {
            return false;
        }
        if (failure instanceof BackendException backend) {
            return !backend.isTransient();
        }
        if (failure instanceof CustomException custom) {
            return custom.getHttpStatus() != null && custom.getHttpStatus().is4xxClientError();
        }
        return failure instanceof IllegalArgumentException
                || failure instanceof JacksonException
                || failure instanceof DateTimeException;
    }

    /** A backend outage (no response or a 5xx) or a 5xx answer built from one. */
    private static boolean isTransientBackendFailure(Throwable failure) {
        if (failure instanceof BackendException backend) {
            return backend.isTransient();
        }
        return failure instanceof CustomException custom && custom.getHttpStatus() != null
                && custom.getHttpStatus().is5xxServerError();
    }

    private static boolean involves(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean involvesInfrastructure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (current instanceof DataAccessException
                    || current instanceof TransactionException
                    || current instanceof PersistenceException
                    || current instanceof SQLException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

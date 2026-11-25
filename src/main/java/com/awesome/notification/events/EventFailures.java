package com.awesome.notification.events;

import com.awesome.notification.client.BackendException;
import com.awesome.notification.controller.exception.CustomException;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import tools.jackson.core.JacksonException;

import java.sql.SQLException;
import java.time.DateTimeException;

/**
 * Tells a poison event (one whose own content can never be handled) from a failure of the
 * infrastructure around it. Only a poison event may be skipped by the poller; anything else stops
 * the poll with the cursor unchanged, so the next poll retries the event.
 *
 * <p>Poison: {@link IllegalArgumentException} (malformed data, unknown template, unparsable id or
 * number), a Jackson parse or mapping failure, an unparsable date, a backend 4xx about the event
 * ({@link BackendException} that is not transient) and a {@link CustomException} with a 4xx status
 * (for example a missing template property). A failure with a {@link DataAccessException},
 * {@link TransactionException}, JPA {@link PersistenceException} or {@link SQLException} anywhere
 * in its cause chain (a dropped connection, a pool or query timeout, a failed commit) is never
 * poison, whatever wraps it; neither is any other unexpected exception.
 */
final class EventFailures {

    private static final int MAX_DEPTH = 16;

    private EventFailures() {
    }

    static boolean isPoison(Throwable failure) {
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

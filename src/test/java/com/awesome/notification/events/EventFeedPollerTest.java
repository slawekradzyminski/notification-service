package com.awesome.notification.events;

import com.awesome.notification.client.BackendClient;
import com.awesome.notification.client.BackendErrorMapper;
import com.awesome.notification.client.BackendException;
import com.awesome.notification.client.EventFeedPage;
import com.awesome.notification.client.FeedEvent;
import com.awesome.notification.controller.exception.CustomException;
import com.awesome.notification.entity.EventCursorEntity;
import com.awesome.notification.entity.ProcessedEventEntity;
import com.awesome.notification.repository.EventCursorRepository;
import com.awesome.notification.repository.ProcessedEventRepository;
import com.awesome.notification.tracing.Spans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import tools.jackson.core.exc.StreamReadException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.format.DateTimeParseException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Failure handling the flow tests cannot provoke: transient database errors, backend 4xx, unknown types. */
class EventFeedPollerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);

    private final BackendClient backend = mock(BackendClient.class);
    private final EventCursorRepository cursors = mock(EventCursorRepository.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final FeedEventHandler handler = mock(FeedEventHandler.class);
    private final List<UUID> processedIds = new ArrayList<>();
    private EventCursorEntity cursor;

    @BeforeEach
    void setUp() {
        cursor = new EventCursorEntity(EventFeedPoller.CONSUMER, 0L, null);
        when(handler.types()).thenReturn(List.of("card-refund.requested"));
        when(cursors.findById(EventFeedPoller.CONSUMER)).thenAnswer(i -> Optional.of(cursor));
        when(cursors.findForUpdate(EventFeedPoller.CONSUMER)).thenAnswer(i -> Optional.of(cursor));
        when(processed.existsById(any())).thenAnswer(i -> processedIds.contains(i.<UUID>getArgument(0)));
        when(processed.save(any())).thenAnswer(i -> {
            processedIds.add(i.<ProcessedEventEntity>getArgument(0).getEventId());
            return i.getArgument(0);
        });
    }

    @Test
    void aTransientDatabaseFailureStopsThePollWithoutMovingTheCursor() {
        FeedEvent first = event(1, "card-refund.requested");
        FeedEvent second = event(2, "card-refund.requested");
        feed(new EventFeedPage(List.of(first, second), 2, false));
        doThrow(new QueryTimeoutException("lock timeout")).when(handler).handle(first);

        assertThat(poller(true).poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
        assertThat(processedIds).isEmpty();
        verify(handler, never()).handle(second);
    }

    @Test
    void droppedConnectionKeepsCursor() {
        FeedEvent created = event(1, "card-refund.requested");
        feed(new EventFeedPage(List.of(created), 1, false));
        doThrow(new DataAccessResourceFailureException("Unable to acquire JDBC Connection"))
                .doNothing()
                .when(handler).handle(created);
        EventFeedPoller poller = poller(true);

        assertThat(poller.poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
        assertThat(processedIds).isEmpty();

        assertThat(poller.poll()).isEqualTo(1);

        verify(handler, times(2)).handle(created);
        assertThat(cursor.getLastSequence()).isEqualTo(1);
        assertThat(processedIds).containsExactly(created.id());
    }

    static Stream<RuntimeException> infrastructureFailures() {
        return Stream.of(
                new DataAccessResourceFailureException("connection reset"),
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"),
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"),
                new JpaSystemException(new RuntimeException("connection closed")),
                new TransactionSystemException("Could not commit JPA transaction"),
                new QueryTimeoutException("statement timeout"),
                new DataIntegrityViolationException("duplicate key"),
                new IllegalArgumentException("wrapped", new SQLTransientConnectionException("pool exhausted")),
                new jakarta.persistence.PersistenceException("connection lost"),
                new CustomException("Database unavailable", HttpStatus.SERVICE_UNAVAILABLE),
                new IllegalStateException("unexpected"));
    }

    @ParameterizedTest
    @MethodSource("infrastructureFailures")
    void aFailureOutsideTheEventKeepsTheCursorAndStopsThePoll(RuntimeException failure) {
        FeedEvent stuck = event(1, "card-refund.requested");
        FeedEvent next = event(2, "card-refund.requested");
        feed(new EventFeedPage(List.of(stuck, next), 2, false));
        doThrow(failure).when(handler).handle(stuck);

        assertThat(poller(true).poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
        assertThat(processedIds).isEmpty();
        verify(handler, never()).handle(next);
    }

    static Stream<RuntimeException> poisonFailures() {
        return Stream.of(
                new IllegalArgumentException("notification.requested event has no recipientEmail"),
                new NumberFormatException("For input string: \"x\""),
                new StreamReadException(null, "Unexpected character"),
                new DateTimeParseException("bad date", "yesterday", 0),
                new CustomException("properties.orderId is required for ORDER_CONFIRMED", HttpStatus.BAD_REQUEST));
    }

    @ParameterizedTest
    @MethodSource("poisonFailures")
    void aPoisonEventIsStillSkipped(RuntimeException failure) {
        FeedEvent poison = event(1, "card-refund.requested");
        FeedEvent next = event(2, "card-refund.requested");
        feed(new EventFeedPage(List.of(poison, next), 2, false));
        doThrow(failure).when(handler).handle(poison);

        assertThat(poller(true).poll()).isEqualTo(2);

        assertThat(processedIds).containsExactly(poison.id(), next.id());
        assertThat(cursor.getLastSequence()).isEqualTo(2);
    }

    @Test
    void aFailedCommitKeepsTheCursor() {
        FeedEvent first = event(1, "card-refund.requested");
        feed(new EventFeedPage(List.of(first), 1, false));
        InlineTransactionManager failingCommit = new InlineTransactionManager() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                if (!processedIds.isEmpty()) {
                    // the data written in this transaction is lost with the connection
                    processedIds.clear();
                    cursor.setLastSequence(0);
                    throw new TransactionSystemException("Could not commit JPA transaction");
                }
            }
        };

        assertThat(new EventFeedPoller(backend, cursors, processed, List.of(handler), failingCommit, CLOCK,
                Spans.disabled(), true, "feed").poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
        assertThat(processedIds).isEmpty();
    }

    @Test
    void whenAPoisonEventCannotBeRecordedThePollStopsWithoutMovingTheCursor() {
        FeedEvent poison = event(1, "card-refund.requested");
        feed(new EventFeedPage(List.of(poison), 1, false));
        doThrow(new IllegalArgumentException("malformed")).when(handler).handle(poison);
        doThrow(new DataAccessResourceFailureException("connection reset")).when(processed).save(any());

        assertThat(poller(true).poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
    }

    @Test
    void eventsBeforeATransientFailureKeepTheirCursorPosition() {
        FeedEvent first = event(1, "card-refund.requested");
        FeedEvent skipped = event(2, "card-refund.requested");
        FeedEvent stuck = event(3, "card-refund.requested");
        feed(new EventFeedPage(List.of(first, skipped, stuck), 3, false));
        doThrow(new IllegalArgumentException("malformed")).when(handler).handle(skipped);
        doThrow(new QueryTimeoutException("lock timeout")).when(handler).handle(stuck);

        assertThat(poller(true).poll()).isEqualTo(2);

        assertThat(cursor.getLastSequence()).isEqualTo(2);
        assertThat(cursor.getUpdatedAt()).isEqualTo(LocalDateTime.now(CLOCK));
        assertThat(processedIds).containsExactly(first.id(), skipped.id());
    }

    @Test
    void aHandledEventMovesTheCursorEvenWhenTheNextOneIsStuck() {
        FeedEvent first = event(1, "card-refund.requested");
        FeedEvent stuck = event(2, "card-refund.requested");
        feed(new EventFeedPage(List.of(first, stuck), 2, false));
        doThrow(new QueryTimeoutException("lock timeout")).when(handler).handle(stuck);

        assertThat(poller(true).poll()).isEqualTo(1);

        assertThat(cursor.getLastSequence()).isEqualTo(1);
    }

    @Test
    void anOutageOnALaterPageKeepsTheCountOfHandledEvents() {
        FeedEvent first = event(1, "card-refund.requested");
        when(backend.events(anyLong(), anyInt(), anyList()))
                .thenReturn(new EventFeedPage(List.of(first), 1, true))
                .thenThrow(BackendErrorMapper.unavailable(null));

        assertThat(poller(true).poll()).isEqualTo(1);

        assertThat(cursor.getLastSequence()).isEqualTo(1);
    }

    @Test
    void anEventAtTheCursorIsNotHandledAgain() {
        cursor.setLastSequence(5);
        FeedEvent same = event(5, "card-refund.requested");
        feed(new EventFeedPage(List.of(same), 5, false));

        poller(true).poll();

        verify(handler, never()).handle(any());
    }

    @Test
    void aTransientBackendFailureInsideAHandlerIsRetriedLater() {
        FeedEvent first = event(1, "card-refund.requested");
        feed(new EventFeedPage(List.of(first), 1, false));
        doThrow(BackendErrorMapper.unavailable(null)).when(handler).handle(first);

        assertThat(poller(true).poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
    }

    @Test
    void aPermanentBackendRejectionInsideAHandlerSkipsTheEvent() {
        FeedEvent first = event(1, "card-refund.requested");
        feed(new EventFeedPage(List.of(first), 1, false));
        doThrow(new BackendException("Order not found", HttpStatus.NOT_FOUND, 404, null)).when(handler).handle(first);

        assertThat(poller(true).poll()).isEqualTo(1);

        assertThat(cursor.getLastSequence()).isEqualTo(1);
        assertThat(processedIds).containsExactly(first.id());
    }

    @Test
    void aFeedRejectionIsTreatedLikeAnOutage() {
        when(backend.events(anyLong(), anyInt(), anyList()))
                .thenThrow(new BackendException("Bad request", HttpStatus.BAD_REQUEST, 400, null));

        assertThat(poller(true).poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
    }

    @Test
    void anUnrequestedTypeIsRecordedButNotHandled() {
        FeedEvent other = event(3, "order.created");
        feed(new EventFeedPage(List.of(other), 3, false));

        assertThat(poller(true).poll()).isEqualTo(1);

        verify(handler, never()).handle(any());
        assertThat(processedIds).containsExactly(other.id());
        assertThat(cursor.getLastSequence()).isEqualTo(3);
    }

    @Test
    void anEventAtOrBeforeTheCursorIsNotHandledAgain() {
        cursor.setLastSequence(5);
        FeedEvent old = event(4, "card-refund.requested");
        feed(new EventFeedPage(List.of(old), 5, false));

        assertThat(poller(true).poll()).isEqualTo(1);

        verify(handler, never()).handle(any());
        assertThat(cursor.getLastSequence()).isEqualTo(5);
    }

    @Test
    void aMissingCursorRowIsCreatedAtZero() {
        when(cursors.findById(EventFeedPoller.CONSUMER)).thenReturn(Optional.empty());
        when(cursors.findForUpdate(EventFeedPoller.CONSUMER)).thenReturn(Optional.empty());
        when(cursors.saveAndFlush(any())).thenAnswer(i -> {
            cursor = i.getArgument(0);
            return cursor;
        });
        FeedEvent first = event(1, "card-refund.requested");
        feed(new EventFeedPage(List.of(first), 1, false));

        assertThat(poller(true).poll()).isEqualTo(1);

        assertThat(cursor.getConsumer()).isEqualTo(EventFeedPoller.CONSUMER);
        assertThat(cursor.getLastSequence()).isEqualTo(1);
        assertThat(cursor.getUpdatedAt()).isNotNull();
    }

    @Test
    void aPollStopsAfterTheMaximumNumberOfPages() {
        when(backend.events(anyLong(), anyInt(), anyList())).thenAnswer(i -> {
            long after = i.getArgument(0);
            return new EventFeedPage(List.of(event(after + 1, "card-refund.requested")), after + 1, true);
        });

        assertThat(poller(true).poll()).isEqualTo(EventFeedPoller.MAX_PAGES_PER_POLL);

        assertThat(cursor.getLastSequence()).isEqualTo(EventFeedPoller.MAX_PAGES_PER_POLL);
    }

    @Test
    void theScheduledPollHonoursTheSwitch() {
        feed(new EventFeedPage(List.of(), 0, false));

        poller(false).scheduledPoll();
        verify(backend, never()).events(anyLong(), anyInt(), anyList());

        poller(true).scheduledPoll();
        verify(backend).events(0L, EventFeedPoller.PAGE_SIZE, List.of("card-refund.requested"));
    }

    @Test
    void twoHandlersForOneTypeAreAConfigurationError() {
        FeedEventHandler duplicate = mock(FeedEventHandler.class);
        when(duplicate.types()).thenReturn(List.of("other", "card-refund.requested"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new EventFeedPoller(backend, cursors, processed,
                        List.of(handler, duplicate), new InlineTransactionManager(), CLOCK, Spans.disabled(), true, "feed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Two feed event handlers for card-refund.requested");
    }

    @Test
    void eachEventIsHandledUnderItsRequestIdAndTraceId() {
        FeedEvent traced = new FeedEvent(1, UUID.randomUUID(), "card-refund.requested", "2026-11-10T12:00:00Z", 1L,
                "client", null, "req-1", "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331");
        feed(new EventFeedPage(List.of(traced), 1, false));
        List<String> seen = new ArrayList<>();
        org.mockito.Mockito.doAnswer(i -> {
            seen.add(org.slf4j.MDC.get("requestId"));
            seen.add(org.slf4j.MDC.get("traceId"));
            return null;
        }).when(handler).handle(traced);

        poller(true).poll();

        assertThat(seen).containsExactly("req-1", "0af7651916cd43dd8448eb211c80319c");
        assertThat(org.slf4j.MDC.get("traceId")).isNull();
    }

    @Test
    void theCursorMovesPastEventsTheBackendFilteredOut() {
        feed(new EventFeedPage(List.of(), 7, false));

        assertThat(poller(true).poll()).isZero();

        assertThat(cursor.getLastSequence()).isEqualTo(7);
        assertThat(cursor.getUpdatedAt()).isNotNull();
    }

    private void feed(EventFeedPage page) {
        when(backend.events(anyLong(), anyInt(), anyList())).thenReturn(page);
    }

    private static FeedEvent event(long sequence, String type) {
        return new FeedEvent(sequence, UUID.randomUUID(), type, "2026-11-10T12:00:00Z", 1L, "client", null);
    }

    private EventFeedPoller poller(boolean enabled) {
        return new EventFeedPoller(backend, cursors, processed, List.of(handler), new InlineTransactionManager(), CLOCK,
                Spans.disabled(), enabled, "feed");
    }

    static class InlineTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // runs inline
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // nothing to commit
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // nothing to roll back
        }
    }
}

package com.awesome.notification.service.email;

import com.awesome.notification.dto.email.EmailDto;
import com.awesome.notification.dto.email.EmailTemplate;
import com.awesome.notification.dto.email.NotificationEmailStatus;
import com.awesome.notification.entity.NotificationEmailEntity;
import com.awesome.notification.metrics.OutboxMetrics;
import com.awesome.notification.repository.NotificationEmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Outbox convention (ADR 3) of commerce emails: idempotent record, claim, backoff 30 s doubling to 30 min, 10 attempts. */
class NotificationEmailOutboxTest {

    private static final Instant NOW = Instant.parse("2026-11-10T20:40:33Z");

    private final NotificationEmailRepository emails = mock(NotificationEmailRepository.class);
    private final OutboxMetrics metrics = mock(OutboxMetrics.class);
    private final NotificationEmailProperties properties = new NotificationEmailProperties();
    private NotificationEmailOutbox outbox;
    private NotificationEmailEntity row;

    @BeforeEach
    void setUp() {
        outbox = new NotificationEmailOutbox(emails, properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
        row = NotificationEmailEntity.builder().id(UUID.randomUUID()).requestId(UUID.randomUUID()).eventId(UUID.randomUUID())
                .username("alice").recipientEmail("alice@example.com").template(EmailTemplate.ORDER_DELIVERED)
                .subject("Order #7 was delivered").message("Hi alice").properties("{\"orderId\":\"7\",\"username\":\"alice\"}")
                .status(NotificationEmailStatus.QUEUED).attempts(0).nextAttemptAt(NOW).createdAt(NOW).updatedAt(NOW)
                .correlationRequestId("req-1").traceparent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                .build();
        when(emails.findById(row.getId())).thenReturn(Optional.of(row));
        when(emails.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @ParameterizedTest
    @CsvSource({"0,30", "1,30", "2,60", "3,120", "4,240", "6,960", "7,1800", "9,1800", "40,1800"})
    void backoffStartsAtThirtySecondsDoublesAndIsCappedAtThirtyMinutes(int attempts, long seconds) {
        assertThat(NotificationEmailOutbox.backoff(attempts, Duration.ofSeconds(30), Duration.ofMinutes(30)))
                .isEqualTo(Duration.ofSeconds(seconds));
    }

    @Test
    void recordsAQueuedEmailDueNowWithTheCurrentCorrelation() {
        // given
        UUID requestId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MDC.put("requestId", "req-9");
        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c");
        MDC.put("spanId", "b7ad6b7169203331");

        // when
        Optional<UUID> id;
        try {
            id = outbox.record(requestId, eventId, "alice", email());
        } finally {
            MDC.clear();
        }

        // then
        ArgumentCaptor<NotificationEmailEntity> saved = ArgumentCaptor.forClass(NotificationEmailEntity.class);
        verify(emails).save(saved.capture());
        NotificationEmailEntity entity = saved.getValue();
        assertThat(id).contains(entity.getId());
        assertThat(entity.getRequestId()).isEqualTo(requestId);
        assertThat(entity.getEventId()).isEqualTo(eventId);
        assertThat(entity.getUsername()).isEqualTo("alice");
        assertThat(entity.getRecipientEmail()).isEqualTo("alice@example.com");
        assertThat(entity.getTemplate()).isEqualTo(EmailTemplate.PAYMENT_FAILED);
        assertThat(entity.getSubject()).isEqualTo("Subject");
        assertThat(entity.getMessage()).isEqualTo("Body");
        assertThat(entity.getProperties()).isEqualTo("{\"orderId\":\"1\",\"username\":\"alice\"}");
        assertThat(entity.getStatus()).isEqualTo(NotificationEmailStatus.QUEUED);
        assertThat(entity.getAttempts()).isZero();
        assertThat(entity.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
        assertThat(entity.getCorrelationRequestId()).isEqualTo("req-9");
        assertThat(entity.getTraceparent()).isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    }

    @Test
    void aKnownRequestIdIsNotRecordedAgain() {
        UUID requestId = UUID.randomUUID();
        when(emails.existsByRequestId(requestId)).thenReturn(true);

        assertThat(outbox.record(requestId, UUID.randomUUID(), "alice", email())).isEmpty();
        verify(emails, never()).save(any());
    }

    @Test
    void claimLeasesTheEmailAndRebuildsItAsStored() {
        // given
        when(emails.claim(eq(row.getId()), eq(NotificationEmailStatus.QUEUED), eq(NOW), any())).thenReturn(1);

        // when
        Optional<NotificationEmailOutbox.Attempt> attempt = outbox.claim(row.getId());

        // then
        ArgumentCaptor<Instant> lease = ArgumentCaptor.forClass(Instant.class);
        verify(emails).claim(eq(row.getId()), eq(NotificationEmailStatus.QUEUED), eq(NOW), lease.capture());
        assertThat(lease.getValue()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        assertThat(attempt).hasValueSatisfying(claimed -> {
            assertThat(claimed.id()).isEqualTo(row.getId());
            assertThat(claimed.requestId()).isEqualTo("req-1");
            assertThat(claimed.traceparent()).isEqualTo(row.getTraceparent());
            assertThat(claimed.email().getTo()).isEqualTo("alice@example.com");
            assertThat(claimed.email().getSubject()).isEqualTo("Order #7 was delivered");
            assertThat(claimed.email().getMessage()).isEqualTo("Hi alice");
            assertThat(claimed.email().getTemplate()).isEqualTo(EmailTemplate.ORDER_DELIVERED);
            assertThat(claimed.email().getProperties()).containsExactly(Map.entry("orderId", "7"),
                    Map.entry("username", "alice"));
        });
    }

    @Test
    void claimFailsWhenAnotherSenderHoldsTheEmail() {
        when(emails.claim(any(), any(), any(), any())).thenReturn(0);
        assertThat(outbox.claim(row.getId())).isEmpty();
    }

    @Test
    void aSuccessfulAttemptMarksTheEmailSent() {
        row.setLastError("earlier");

        NotificationEmailEntity recorded = outbox.recordAttempt(row.getId(), null);

        assertThat(recorded).isSameAs(row);
        assertThat(row.getStatus()).isEqualTo(NotificationEmailStatus.SENT);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getSentAt()).isEqualTo(NOW);
        assertThat(row.getNextAttemptAt()).isNull();
        assertThat(row.getLastError()).isNull();
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        verifyNoInteractions(metrics);
    }

    @Test
    void failedAttemptsStayQueuedWithBackoffUntilTheTenthIsFinal() {
        // given
        row.setUpdatedAt(NOW.minusSeconds(60));

        // when
        NotificationEmailEntity recorded = outbox.recordAttempt(row.getId(), "JmsException: broker down");

        // then
        assertThat(recorded).isSameAs(row);
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        assertThat(row.getStatus()).isEqualTo(NotificationEmailStatus.QUEUED);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(row.getLastError()).isEqualTo("JmsException: broker down");
        assertThat(row.getSentAt()).isNull();

        // when
        outbox.recordAttempt(row.getId(), "again");

        // then
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));

        // when
        row.setAttempts(8);
        outbox.recordAttempt(row.getId(), "ninth");

        // then
        assertThat(row.getStatus()).isEqualTo(NotificationEmailStatus.QUEUED);
        verifyNoInteractions(metrics);

        // when
        NotificationEmailEntity failed = outbox.recordAttempt(row.getId(), "tenth");

        // then
        assertThat(failed).isSameAs(row);
        assertThat(row.getStatus()).isEqualTo(NotificationEmailStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo(10);
        assertThat(row.getNextAttemptAt()).isNull();
        verify(metrics).givenUp("email");
    }

    @Test
    void longErrorsAreTruncated() {
        outbox.recordAttempt(row.getId(), "e".repeat(1_500));
        assertThat(row.getLastError()).hasSize(1_000);
        outbox.recordAttempt(row.getId(), "f".repeat(1_000));
        assertThat(row.getLastError()).hasSize(1_000);
    }

    @Test
    void dueIdsAskForQueuedEmailsDueNow() {
        List<UUID> due = List.of(UUID.randomUUID());
        when(emails.findDueIds(eq(NotificationEmailStatus.QUEUED), eq(NOW), any())).thenReturn(due);

        assertThat(outbox.dueIds(5)).isEqualTo(due);
    }

    private static EmailDto email() {
        return EmailDto.builder().to("alice@example.com").subject("Subject").message("Body")
                .template(EmailTemplate.PAYMENT_FAILED)
                .properties(new java.util.LinkedHashMap<>(Map.of("orderId", "1")) {{
                    put("username", "alice");
                }})
                .build();
    }
}

package com.awesome.notification.service.email;

import com.awesome.notification.correlation.Correlation;
import com.awesome.notification.dto.email.EmailDto;
import com.awesome.notification.dto.email.NotificationEmailStatus;
import com.awesome.notification.entity.NotificationEmailEntity;
import com.awesome.notification.metrics.OutboxMetrics;
import com.awesome.notification.repository.NotificationEmailRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable state of commerce emails (outbox convention, ADR 3): recording a rendered email in the
 * feed poller's transaction (idempotent per {@code requestId}), claiming a due one for an attempt and
 * recording the outcome. Backoff: 30 seconds after the first failure, doubling, capped at 30 minutes;
 * after 10 attempts the email is FAILED and counted in {@code notification.outbox.failed}.
 */
@Service
public class NotificationEmailOutbox {

    static final int MAX_ERROR = 1000;
    private static final int CLAIMED = 1;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> PROPERTIES = new TypeReference<>() {
    };

    private final NotificationEmailRepository emails;
    private final NotificationEmailProperties properties;
    private final OutboxMetrics metrics;
    private final Clock clock;

    /** Everything one attempt needs, read in the claiming transaction. */
    public record Attempt(UUID id, EmailDto email, String requestId, String traceparent) {
    }

    public NotificationEmailOutbox(NotificationEmailRepository emails, NotificationEmailProperties properties,
                                   OutboxMetrics metrics, Clock clock) {
        this.emails = emails;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Records a QUEUED email, due now, in the caller's transaction; empty when one with this
     * {@code requestId} exists already (a redelivered or repeated request).
     */
    @Transactional
    public Optional<UUID> record(UUID requestId, UUID eventId, String username, EmailDto email) {
        if (emails.existsByRequestId(requestId)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        NotificationEmailEntity saved = emails.save(NotificationEmailEntity.builder()
                .id(UUID.randomUUID())
                .requestId(requestId)
                .eventId(eventId)
                .username(username)
                .recipientEmail(email.getTo())
                .template(email.getTemplate())
                .subject(email.getSubject())
                .message(email.getMessage())
                .properties(JSON.writeValueAsString(email.getProperties()))
                .status(NotificationEmailStatus.QUEUED)
                .attempts(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .correlationRequestId(Correlation.currentRequestId())
                .traceparent(Correlation.currentTraceparent())
                .build());
        return Optional.of(saved.getId());
    }

    /** Claims a due QUEUED email for one attempt; empty when another sender holds it or it is not due. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Attempt> claim(UUID id) {
        Instant now = clock.instant();
        if (emails.claim(id, NotificationEmailStatus.QUEUED, now, now.plus(LEASE)) != CLAIMED) {
            return Optional.empty();
        }
        NotificationEmailEntity row = emails.findById(id).orElseThrow();
        EmailDto email = EmailDto.builder()
                .to(row.getRecipientEmail())
                .subject(row.getSubject())
                .message(row.getMessage())
                .template(row.getTemplate())
                .properties(JSON.readValue(row.getProperties(), PROPERTIES))
                .build();
        return Optional.of(new Attempt(row.getId(), email, row.getCorrelationRequestId(), row.getTraceparent()));
    }

    /** Records an attempt: SENT when {@code error} is null, otherwise QUEUED with backoff or FAILED at the limit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationEmailEntity recordAttempt(UUID id, String error) {
        Instant now = clock.instant();
        NotificationEmailEntity row = emails.findById(id).orElseThrow();
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        row.setUpdatedAt(now);
        if (error == null) {
            row.setStatus(NotificationEmailStatus.SENT);
            row.setSentAt(now);
            row.setNextAttemptAt(null);
            row.setLastError(null);
            return row;
        }
        row.setLastError(error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR));
        if (attempts >= properties.getMaxAttempts()) {
            row.setStatus(NotificationEmailStatus.FAILED);
            row.setNextAttemptAt(null);
            metrics.givenUp("email");
        } else {
            row.setNextAttemptAt(now.plus(backoff(attempts, properties.getInitialBackoff(), properties.getMaxBackoff())));
        }
        return row;
    }

    @Transactional(readOnly = true)
    public List<UUID> dueIds(int limit) {
        return emails.findDueIds(NotificationEmailStatus.QUEUED, clock.instant(), PageRequest.of(0, limit));
    }

    /** {@code initial} after the 1st failed attempt, doubling with every further one, never above {@code max}. */
    static Duration backoff(int attempts, Duration initial, Duration max) {
        Duration delay = initial.multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 20));
        return delay.compareTo(max) > 0 ? max : delay;
    }
}

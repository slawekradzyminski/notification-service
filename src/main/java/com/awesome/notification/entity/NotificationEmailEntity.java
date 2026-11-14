package com.awesome.notification.entity;

import com.awesome.notification.dto.email.EmailTemplate;
import com.awesome.notification.dto.email.NotificationEmailStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One commerce email from a {@code notification.requested} event: rendered once, then sent (and
 * retried) as stored. Outbox convention (ADR 3): pending while {@code sentAt} is null and attempts are
 * below the maximum.
 */
@Entity
@Table(name = "notification_emails")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEmailEntity {

    @Id
    private UUID id;

    /** The backend's idempotency key; an email is never recorded twice for one requestId. */
    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private String username;

    @Column(name = "recipient_email", nullable = false, length = 320)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private EmailTemplate template;

    @Column(nullable = false, length = 1000)
    private String subject;

    @Column(nullable = false, length = 16384)
    private String message;

    /** JSON object of the template properties sent with the email. */
    @Column(nullable = false, length = 16384)
    private String properties;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationEmailStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "correlation_request_id", length = 128)
    private String correlationRequestId;

    @Column(length = 55)
    private String traceparent;
}

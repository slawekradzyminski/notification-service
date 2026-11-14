package com.awesome.notification.dto.email;

import com.awesome.notification.entity.NotificationEmailEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A commerce email sent (or being sent) to a customer")
public record NotificationEmailDto(
        @Schema(description = "Email id", example = "0b8f4f0e-3f43-4b8e-9d55-4a3f1c1f6a10") UUID id,
        @Schema(description = "Email template", example = "PAYMENT_RECEIVED") EmailTemplate template,
        @Schema(description = "Rendered subject", example = "Payment received for order #42") String subject,
        @Schema(description = "Delivery status", example = "SENT") NotificationEmailStatus status,
        @Schema(description = "Masked recipient email address", example = "j***@example.com") String recipientMasked,
        @Schema(description = "Send attempts so far", example = "1") int attempts,
        @Schema(description = "When the email was queued (UTC)", example = "2026-11-10T20:40:33Z") Instant createdAt,
        @Schema(description = "Last status change (UTC)", example = "2026-11-10T20:40:34Z") Instant updatedAt,
        @Schema(types = {"string", "null"}, format = "date-time", description = "When the transport accepted it")
        Instant sentAt,
        @Schema(types = {"string", "null"}, description = "Why the last attempt failed", example = "Broker unavailable")
        String failureReason) {

    public static NotificationEmailDto from(NotificationEmailEntity entity) {
        return new NotificationEmailDto(entity.getId(), entity.getTemplate(), entity.getSubject(), entity.getStatus(),
                maskRecipient(entity.getRecipientEmail()), entity.getAttempts(), entity.getCreatedAt(),
                entity.getUpdatedAt(), entity.getSentAt(), entity.getLastError());
    }

    /** The backend's masking of email events: first character, {@code ***}, then the domain. */
    static String maskRecipient(String email) {
        int atIndex = email == null ? -1 : email.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}

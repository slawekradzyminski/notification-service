package com.awesome.notification.controller.email;

import com.awesome.notification.dto.ErrorDto;
import com.awesome.notification.jms.EmailTransport;
import com.awesome.notification.service.email.LocalEmailOutbox;
import com.awesome.notification.service.email.NotificationEmailProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * The commerce emails captured by the {@code local-outbox} transport, guarded like the backend's
 * {@code /api/v1/local/email/outbox}: administrators only, plus {@code X-Local-Outbox-Key} when
 * {@code LOCAL_EMAIL_OUTBOX_ACCESS_KEY} is set; 404 (and absent from OpenAPI) with the {@code jms} transport.
 */
@RestController
@RequestMapping(LocalNotificationOutboxController.PATH)
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "Unauthorized or invalid Bearer token",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
@ApiResponse(responseCode = "403", description = "Administrator role required, or configured X-Local-Outbox-Key is missing or invalid",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
@ApiResponse(responseCode = "404", description = "The local outbox is not in use (JMS transport)",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
@Tag(name = "local-notification-outbox", description = "Commerce emails captured instead of being sent (local outbox transport)")
public class LocalNotificationOutboxController {

    public static final String PATH = "/api/v1/local/notifications/outbox";
    static final String OUTBOX_ACCESS_KEY_HEADER = "X-Local-Outbox-Key";

    private final LocalEmailOutbox localEmailOutbox;
    private final boolean active;
    private final String accessKey;

    public LocalNotificationOutboxController(LocalEmailOutbox localEmailOutbox, NotificationEmailProperties properties) {
        this.localEmailOutbox = localEmailOutbox;
        this.active = properties.transportType() == EmailTransport.LOCAL_OUTBOX;
        this.accessKey = properties.getLocalOutbox().getAccessKey() == null ? "" : properties.getLocalOutbox().getAccessKey();
    }

    @GetMapping
    @Operation(summary = "Fetch the commerce emails captured by the local outbox",
            description = "Oldest first, exactly as they would have been sent to the JMS queue. Requires an administrator "
                    + "and, when LOCAL_EMAIL_OUTBOX_ACCESS_KEY is set, X-Local-Outbox-Key.")
    @ApiResponse(responseCode = "200", description = "Captured emails returned")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<LocalEmailOutbox.StoredEmail>> getOutbox(
            @RequestHeader(name = OUTBOX_ACCESS_KEY_HEADER, required = false) String suppliedKey) {
        requireAccess(suppliedKey);
        return ResponseEntity.ok(localEmailOutbox.getAll());
    }

    @DeleteMapping
    @Operation(summary = "Clear the local outbox", description = "Deletes every captured email.")
    @ApiResponse(responseCode = "200", description = "Outbox cleared", content = @Content)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> clearOutbox(
            @RequestHeader(name = OUTBOX_ACCESS_KEY_HEADER, required = false) String suppliedKey) {
        requireAccess(suppliedKey);
        localEmailOutbox.clear();
        return ResponseEntity.ok().build();
    }

    public boolean isActive() {
        return active;
    }

    private void requireAccess(String suppliedKey) {
        if (!active) {
            throw new com.awesome.notification.controller.exception.CustomException("Resource not found",
                    HttpStatus.NOT_FOUND);
        }
        if (accessKey.isBlank()) {
            return;
        }
        byte[] expected = accessKey.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = suppliedKey == null ? new byte[0] : suppliedKey.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new AccessDeniedException("Invalid local outbox access key");
        }
    }
}

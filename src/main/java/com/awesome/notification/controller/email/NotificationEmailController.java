package com.awesome.notification.controller.email;

import com.awesome.notification.dto.ErrorDto;
import com.awesome.notification.dto.PageDto;
import com.awesome.notification.dto.email.NotificationEmailDto;
import com.awesome.notification.security.CustomPrincipal;
import com.awesome.notification.service.email.NotificationEmailQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications/emails")
@Tag(name = "Notifications", description = "Commerce emails sent to you (payments, shipments, returns, loyalty, gift cards)")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "Unauthorized",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
public class NotificationEmailController {

    private final NotificationEmailQueries queries;

    @GetMapping
    @Operation(summary = "List my commerce emails",
            description = "Commerce emails of the caller, newest first, with the recipient masked. Administrators may "
                    + "pass username to read another user's. Negative page is clamped to zero; size is clamped to 1–100.")
    @ApiResponse(responseCode = "200", description = "Emails returned")
    @ApiResponse(responseCode = "400", description = "Invalid parameter",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "403", description = "username names another user and the caller is not an administrator",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public PageDto<NotificationEmailDto> list(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @Parameter(description = "Another user's emails (administrators only)") @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.list(principal, username, page, size);
    }
}

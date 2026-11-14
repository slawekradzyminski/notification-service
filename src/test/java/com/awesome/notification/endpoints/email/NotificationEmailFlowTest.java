package com.awesome.notification.endpoints.email;

import com.awesome.notification.client.FeedEvent;
import com.awesome.notification.dto.ErrorDto;
import com.awesome.notification.dto.PageDto;
import com.awesome.notification.dto.email.EmailTemplate;
import com.awesome.notification.dto.email.NotificationEmailDto;
import com.awesome.notification.dto.email.NotificationEmailStatus;
import com.awesome.notification.entity.NotificationEmailEntity;
import com.awesome.notification.service.email.LocalEmailOutbox;
import com.awesome.notification.support.AbstractFlowTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commerce emails end to end: {@code notification.requested} feed events are rendered, recorded and
 * sent with the local outbox transport, and listed by {@code GET /api/v1/notifications/emails}.
 */
class NotificationEmailFlowTest extends AbstractFlowTest {

    static final String EMAILS = "/api/v1/notifications/emails";
    static final String OUTBOX = "/api/v1/local/notifications/outbox";
    private static final ParameterizedTypeReference<PageDto<NotificationEmailDto>> EMAIL_PAGE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<LocalEmailOutbox.StoredEmail>> STORED =
            new ParameterizedTypeReference<>() {
            };

    private String clientName;
    private String clientToken;
    private String adminToken;

    @BeforeEach
    void createUsers() {
        clientName = randomUsername();
        clientToken = getToken(clientName, "ROLE_CLIENT");
        adminToken = getToken(randomUsername(), "ROLE_ADMIN");
    }

    @Test
    void aRequestedEmailIsRenderedSentToTheOutboxAndListed() {
        // given
        UUID requestId = UUID.randomUUID();
        backend.publish(UUID.randomUUID(), "notification.requested", clientName,
                request(requestId, "PAYMENT_RECEIVED", "jane.doe@example.com", "{\"orderId\":\"42\",\"amount\":\"49.99\"}"),
                "pay-req-1", "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331");

        // when
        int handled = poll();

        // then
        assertThat(handled).isEqualTo(1);
        List<LocalEmailOutbox.StoredEmail> outbox = executeGet(OUTBOX, getHeadersWith(adminToken), STORED).getBody();
        assertThat(outbox).singleElement().satisfies(stored -> {
            assertThat(stored.destination()).isEqualTo("email");
            assertThat(stored.timestamp()).isEqualTo(clock.instant());
            assertThat(stored.payload().getTo()).isEqualTo("jane.doe@example.com");
            assertThat(stored.payload().getSubject()).isEqualTo("Payment received for order #42");
            assertThat(stored.payload().getMessage()).isEqualTo("Hi " + clientName + ",\n\nWe received your payment of "
                    + "49.99 USD for order #42. We will let you know when it ships.");
            assertThat(stored.payload().getTemplate()).isEqualTo(EmailTemplate.PAYMENT_RECEIVED);
            assertThat(stored.payload().getProperties()).containsExactly(Map.entry("orderId", "42"),
                    Map.entry("amount", "49.99"), Map.entry("username", clientName));
        });
        ResponseEntity<PageDto<NotificationEmailDto>> mine = executeGet(EMAILS, getHeadersWith(clientToken), EMAIL_PAGE);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody().getContent()).singleElement().satisfies(email -> {
            assertThat(email.template()).isEqualTo(EmailTemplate.PAYMENT_RECEIVED);
            assertThat(email.status()).isEqualTo(NotificationEmailStatus.SENT);
            assertThat(email.recipientMasked()).isEqualTo("j***@example.com");
            assertThat(email.subject()).isEqualTo("Payment received for order #42");
            assertThat(email.attempts()).isEqualTo(1);
            assertThat(email.sentAt()).isEqualTo(clock.instant());
            assertThat(email.failureReason()).isNull();
        });
        assertThat(mine.getBody().toString()).doesNotContain("jane.doe");
        NotificationEmailEntity stored = emailRepository.findAll().getFirst();
        assertThat(stored.getRequestId()).isEqualTo(requestId);
        assertThat(stored.getCorrelationRequestId()).isEqualTo("pay-req-1");
        assertThat(stored.getTraceparent()).startsWith("00-0af7651916cd43dd8448eb211c80319c-");
    }

    @Test
    void theSameRequestIdIsSentOnlyOnce() {
        // given
        UUID requestId = UUID.randomUUID();
        FeedEvent first = backend.publish("notification.requested", clientName,
                request(requestId, "ORDER_DELIVERED", "buyer@example.com", "{\"orderId\":\"7\"}"));
        backend.publish("notification.requested", clientName,
                request(requestId, "ORDER_DELIVERED", "buyer@example.com", "{\"orderId\":\"7\"}"));
        backend.republish(first);

        // when
        int handled = poll();

        // then
        assertThat(handled).isEqualTo(3);
        assertThat(emailRepository.count()).isEqualTo(1);
        assertThat(localEmailOutbox.getAll()).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("templates")
    void everyCommerceTemplateArrivesRendered(String template, String properties, String subject) {
        // given
        backend.publish("notification.requested", clientName,
                request(UUID.randomUUID(), template, "buyer@example.com", properties));

        // when
        poll();

        // then
        assertThat(localEmailOutbox.getAll()).singleElement().satisfies(stored -> {
            assertThat(stored.payload().getTemplate()).isEqualTo(EmailTemplate.valueOf(template));
            assertThat(stored.payload().getSubject()).isEqualTo(subject);
            assertThat(stored.payload().getMessage()).startsWith("Hi " + clientName + ",\n\n");
        });
    }

    static Stream<Arguments> templates() {
        return Stream.of(
                Arguments.of("PAYMENT_RECEIVED", "{\"orderId\":\"1\",\"amount\":\"9.99\",\"currency\":\"EUR\"}",
                        "Payment received for order #1"),
                Arguments.of("PAYMENT_FAILED", "{\"orderId\":\"1\",\"failureCode\":\"CARD_DECLINED\"}",
                        "Payment for order #1 failed"),
                Arguments.of("BACK_IN_STOCK", "{\"productId\":\"4\",\"productName\":\"Lamp\"}", "Lamp is back in stock"),
                Arguments.of("ORDER_SHIPPED", "{\"orderId\":\"1\",\"carrier\":\"DPD\",\"trackingNumber\":\"T1\"}",
                        "Order #1 has shipped"),
                Arguments.of("ORDER_DELIVERED", "{\"orderId\":\"1\"}", "Order #1 was delivered"),
                Arguments.of("RETURN_UPDATED", "{\"returnId\":\"3\",\"orderId\":\"1\",\"status\":\"APPROVED\"}",
                        "Return #3 is APPROVED"),
                Arguments.of("SHIPMENT_OUT_FOR_DELIVERY", "{\"orderId\":\"1\",\"carrier\":\"DPD\",\"trackingNumber\":\"T1\"}",
                        "Order #1 is out for delivery"),
                Arguments.of("SHIPMENT_DELIVERY_FAILED", "{\"orderId\":\"1\",\"carrier\":\"DPD\",\"trackingNumber\":\"T1\"}",
                        "Delivery of order #1 failed"),
                Arguments.of("LOYALTY_POINTS_EARNED", "{\"points\":120,\"balance\":450}", "You earned 120 loyalty points"),
                Arguments.of("GIFT_CARD_ISSUED", "{\"code\":\"GC-1\",\"amount\":\"50.00\"}", "Your gift card is ready"));
    }

    @Test
    void malformedRequestsAreSkippedWithoutBlockingTheFeed() {
        // given
        backend.publish("notification.requested", clientName,
                request(UUID.randomUUID(), "PASSWORD_RESET_REQUESTED", "buyer@example.com", "{}"));
        backend.publish("notification.requested", clientName,
                request(UUID.randomUUID(), "ORDER_DELIVERED", "buyer@example.com", "{}"));
        backend.publish("notification.requested", clientName, "[]");
        backend.publish("notification.requested", clientName,
                request(UUID.randomUUID(), "ORDER_DELIVERED", "buyer@example.com", "{\"orderId\":\"9\"}"));

        // when
        int handled = poll();

        // then
        assertThat(handled).isEqualTo(4);
        assertThat(localEmailOutbox.getAll()).singleElement()
                .satisfies(stored -> assertThat(stored.payload().getSubject()).isEqualTo("Order #9 was delivered"));
        assertThat(processedEvents.count()).isEqualTo(4);
    }

    @Test
    void emailsAreListedNewestFirstPerUserAndAdminsMayNameAUser() {
        // given
        for (int i = 1; i <= 3; i++) {
            backend.publish("notification.requested", clientName,
                    request(UUID.randomUUID(), "ORDER_DELIVERED", "buyer@example.com", "{\"orderId\":\"" + i + "\"}"));
            poll();
            clock.advance(Duration.ofSeconds(1));
        }
        String otherToken = getToken(randomUsername(), "ROLE_CLIENT");

        // when
        ResponseEntity<PageDto<NotificationEmailDto>> page = executeGet(EMAILS + "?page=0&size=2",
                getHeadersWith(clientToken), EMAIL_PAGE);
        ResponseEntity<PageDto<NotificationEmailDto>> clamped = executeGet(EMAILS + "?page=-3&size=500",
                getHeadersWith(clientToken), EMAIL_PAGE);
        ResponseEntity<PageDto<NotificationEmailDto>> forAdmin = executeGet(EMAILS + "?username=" + clientName,
                getHeadersWith(adminToken), EMAIL_PAGE);
        ResponseEntity<PageDto<NotificationEmailDto>> ownName = executeGet(EMAILS + "?username=" + clientName,
                getHeadersWith(clientToken), EMAIL_PAGE);
        ResponseEntity<PageDto<NotificationEmailDto>> others = executeGet(EMAILS, getHeadersWith(otherToken), EMAIL_PAGE);
        ResponseEntity<ErrorDto> forbidden = executeGet(EMAILS + "?username=" + clientName, getHeadersWith(otherToken),
                ErrorDto.class);
        ResponseEntity<ErrorDto> anonymous = executeGet(EMAILS, getJsonOnlyHeaders(), ErrorDto.class);
        ResponseEntity<Map<String, String>> badPage = executeGet(EMAILS + "?page=x", getHeadersWith(clientToken),
                new ParameterizedTypeReference<>() {
                });

        // then
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody().getTotalElements()).isEqualTo(3);
        assertThat(page.getBody().getContent()).extracting(NotificationEmailDto::subject)
                .containsExactly("Order #3 was delivered", "Order #2 was delivered");
        assertThat(clamped.getBody().getPageNumber()).isZero();
        assertThat(clamped.getBody().getPageSize()).isEqualTo(100);
        assertThat(forAdmin.getBody().getTotalElements()).isEqualTo(3);
        assertThat(ownName.getBody().getTotalElements()).isEqualTo(3);
        assertThat(others.getBody().getTotalElements()).isZero();
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody().getMessage()).isEqualTo("Only administrators can read another user's emails");
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(badPage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badPage.getBody()).containsEntry("error", "Invalid page 'x': must be a whole number");
    }

    @Test
    void theLocalOutboxIsForAdministratorsAndCanBeCleared() {
        // given
        backend.publish("notification.requested", clientName,
                request(UUID.randomUUID(), "ORDER_DELIVERED", "buyer@example.com", "{\"orderId\":\"5\"}"));
        poll();

        // when
        ResponseEntity<ErrorDto> client = executeGet(OUTBOX, getHeadersWith(clientToken), ErrorDto.class);
        ResponseEntity<ErrorDto> anonymous = executeGet(OUTBOX, getJsonOnlyHeaders(), ErrorDto.class);
        ResponseEntity<ErrorDto> clientClear = executeDelete(OUTBOX, getHeadersWith(clientToken), ErrorDto.class);
        ResponseEntity<Void> cleared = executeDelete(OUTBOX, getHeadersWith(adminToken), Void.class);
        ResponseEntity<List<LocalEmailOutbox.StoredEmail>> after = executeGet(OUTBOX, getHeadersWith(adminToken), STORED);

        // then
        assertThat(client.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(client.getBody().getMessage()).isEqualTo("Access denied");
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(clientClear.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(after.getBody()).isEmpty();
    }

    static String request(UUID requestId, String template, String email, String properties) {
        return "{\"requestId\":\"" + requestId + "\",\"template\":\"" + template + "\",\"recipientEmail\":\"" + email
                + "\",\"recipientName\":null,\"properties\":" + properties + "}";
    }
}

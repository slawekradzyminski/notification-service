package com.awesome.notification.service.email;

import com.awesome.notification.dto.email.EmailDto;
import com.awesome.notification.dto.email.EmailTemplate;
import com.awesome.notification.jms.EmailTransport;
import com.awesome.notification.jms.JsonTextMessageConverter;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MessageConversionException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailTransportTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);
    private final EmailDto email = EmailDto.builder().to("user@example.test").subject("Hi").message("Body")
            .template(EmailTemplate.ORDER_DELIVERED).properties(Map.of("orderId", "1")).build();

    @Test
    void localOutboxTransportStoresEmailsWithoutTouchingTheBroker() {
        JmsTemplate jms = mock(JmsTemplate.class);
        NotificationEmailProperties properties = properties("local-outbox");
        LocalEmailOutbox outbox = new LocalEmailOutbox(CLOCK, properties);

        EmailSender sender = new EmailSender(jms, outbox, properties);
        sender.send(email);

        assertThat(sender.transport()).isEqualTo(EmailTransport.LOCAL_OUTBOX);
        assertThat(outbox.getAll()).singleElement().satisfies(stored -> {
            assertThat(stored.payload()).isEqualTo(email);
            assertThat(stored.destination()).isEqualTo("email");
            assertThat(stored.timestamp()).isEqualTo(CLOCK.instant());
        });
        verifyNoInteractions(jms);
    }

    @Test
    void jmsTransportSendsToTheConfiguredQueue() {
        JmsTemplate jms = mock(JmsTemplate.class);
        NotificationEmailProperties properties = properties("jms");
        properties.setDestination("mail-queue");
        LocalEmailOutbox outbox = new LocalEmailOutbox(CLOCK, properties);

        new EmailSender(jms, outbox, properties).send(email);

        verify(jms).convertAndSend("mail-queue", email);
        assertThat(outbox.getAll()).isEmpty();
    }

    @Test
    void transportValuesAreValidated() {
        assertThat(EmailTransport.from(" JMS ")).isEqualTo(EmailTransport.JMS);
        assertThat(EmailTransport.from("local-outbox")).isEqualTo(EmailTransport.LOCAL_OUTBOX);
        assertThatThrownBy(() -> EmailTransport.from("smtp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notification.email.transport");
        assertThatThrownBy(() -> EmailTransport.from(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLocalOutboxKeepsTheNewestEmailsUpToItsCapacity() {
        NotificationEmailProperties properties = properties("local-outbox");
        properties.getLocalOutbox().setCapacity(2);
        LocalEmailOutbox outbox = new LocalEmailOutbox(CLOCK, properties);

        for (int i = 1; i <= 3; i++) {
            outbox.store("email", EmailDto.builder().to(i + "@example.com").build());
        }

        assertThat(outbox.getAll()).extracting(stored -> stored.payload().getTo())
                .containsExactly("2@example.com", "3@example.com");
        outbox.clear();
        assertThat(outbox.getAll()).isEmpty();
    }

    @Test
    void theConverterWritesJsonTextWithTheBackendTypeId() throws Exception {
        Session session = mock(Session.class);
        TextMessage message = mock(TextMessage.class);
        when(session.createTextMessage(anyString())).thenReturn(message);
        JsonTextMessageConverter converter = new JsonTextMessageConverter(JsonMapper.builder().build());

        assertThat(converter.toMessage(email, session)).isSameAs(message);

        verify(session).createTextMessage("{\"to\":\"user@example.test\",\"subject\":\"Hi\",\"message\":\"Body\","
                + "\"template\":\"ORDER_DELIVERED\",\"properties\":{\"orderId\":\"1\"}}");
        verify(message).setStringProperty("_awesome_", "com.awesome.testing.dto.email.EmailDto");
        when(message.getText()).thenReturn("{}");
        assertThat(converter.fromMessage(message)).isEqualTo("{}");
        assertThatThrownBy(() -> converter.fromMessage(mock(jakarta.jms.BytesMessage.class)))
                .isInstanceOf(MessageConversionException.class);
    }

    private static NotificationEmailProperties properties(String transport) {
        NotificationEmailProperties properties = new NotificationEmailProperties();
        properties.setTransport(transport);
        return properties;
    }
}

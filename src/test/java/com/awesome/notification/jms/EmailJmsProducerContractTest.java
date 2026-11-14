package com.awesome.notification.jms;

import com.awesome.notification.dto.ErrorDto;
import com.awesome.notification.dto.email.EmailDto;
import com.awesome.notification.dto.email.EmailTemplate;
import com.awesome.notification.dto.email.NotificationEmailStatus;
import com.awesome.notification.service.email.EmailSender;
import com.awesome.notification.support.AbstractFlowTest;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Producer half of the JMS email contract with jms-email-consumer, moved from the backend: the
 * notification-service must put on the queue exactly the message the backend sent. The fixture
 * {@code contracts/email-jms-contract.json} is byte-identical to the backend's and the consumer's copy.
 * Runs with the {@code jms} transport against an embedded Artemis broker.
 */
@TestPropertySource(properties = {
        "notification.email.transport=jms",
        "spring.artemis.mode=embedded",
        "spring.artemis.embedded.enabled=true",
        "spring.artemis.embedded.queues=email"})
class EmailJmsProducerContractTest extends AbstractFlowTest {

    private static final Duration RECEIVE_DEADLINE = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private EmailSender emailSender;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Test
    void shouldPublishEmailEventMatchingTheConsumerContract() throws Exception {
        JsonNode contract = readContract();
        String destination = contract.get("destination").asString();
        JsonNode expectedPayload = contract.get("payload");

        assertThat(configuredDestination()).as("notification.email.destination in application.yml").isEqualTo(destination);

        emailSender.send(emailFrom(expectedPayload));

        TextMessage message = receive(destination, expectedPayload.get("to").asString());
        assertThat(message.getStringProperty(contract.get("typeIdProperty").asString()))
                .as("JMS type header consumed by JacksonJsonMessageConverter in jms-email-consumer")
                .isEqualTo(contract.get("typeId").asString());
        tools.jackson.databind.node.ObjectNode expected = (tools.jackson.databind.node.ObjectNode) expectedPayload.deepCopy();
        expected.put("template", EmailTemplate.ORDER_DELIVERED.name());
        assertThat(JSON.readTree(message.getText())).as("serialized EmailDto payload").isEqualTo(expected);
    }

    @Test
    void requestedEmailsGoToTheQueueAndTheLocalOutboxIsGone() throws Exception {
        // given
        String username = randomUsername();
        String adminToken = getToken(randomUsername(), "ROLE_ADMIN");
        String recipient = "jms-" + UUID.randomUUID() + "@example.com";
        backend.publish("notification.requested", username, "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"template\":\"GIFT_CARD_ISSUED\",\"recipientEmail\":\"" + recipient
                + "\",\"recipientName\":\"Jane\",\"properties\":{\"code\":\"GC-1\",\"amount\":\"20.00\"}}");

        // when
        poll();

        // then
        TextMessage message = receive("email", recipient);
        JsonNode payload = JSON.readTree(message.getText());
        assertThat(payload.get("subject").asString()).isEqualTo("Your gift card is ready");
        assertThat(payload.get("message").asString()).startsWith("Hi " + username + ",\n\nA gift card worth 20.00 USD");
        assertThat(payload.get("template").asString()).isEqualTo("GIFT_CARD_ISSUED");
        assertThat(emailRepository.findAll()).singleElement()
                .satisfies(email -> assertThat(email.getStatus()).isEqualTo(NotificationEmailStatus.SENT));
        ResponseEntity<ErrorDto> outbox = executeGet("/api/v1/local/notifications/outbox", getHeadersWith(adminToken),
                ErrorDto.class);
        assertThat(outbox.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(localEmailOutbox.getAll()).isEmpty();
        String docs = executeGet("/v3/api-docs", getJsonOnlyHeaders(), String.class).getBody();
        assertThat(docs).contains("/api/v1/notifications/emails").doesNotContain("/api/v1/local/notifications/outbox");
    }

    private static EmailDto emailFrom(JsonNode payload) {
        Map<String, String> properties = new LinkedHashMap<>();
        payload.get("properties").properties().forEach(entry -> properties.put(entry.getKey(), entry.getValue().asString()));
        EmailDto email = EmailDto.builder()
                .to(payload.get("to").asString())
                .subject(payload.get("subject").asString())
                .message(payload.get("message").asString())
                .properties(properties)
                .build();
        // The contract fixture uses an account template the notification-service never sends; the wire format
        // of the template field is its name, so the commerce template is swapped in and back for the comparison.
        email.setTemplate(EmailTemplate.ORDER_DELIVERED);
        return email;
    }

    private TextMessage receive(String destination, String recipient) throws JMSException {
        JmsTemplate receiver = new JmsTemplate(connectionFactory);
        receiver.setReceiveTimeout(500);
        Instant deadline = Instant.now().plus(RECEIVE_DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            Message message = receiver.receive(destination);
            if (message instanceof TextMessage textMessage && textMessage.getText().contains(recipient)) {
                return textMessage;
            }
        }
        return fail("No email for %s arrived on queue '%s' within %s", recipient, destination, RECEIVE_DEADLINE);
    }

    private static JsonNode readContract() throws IOException {
        return JSON.readTree(new ClassPathResource("contracts/email-jms-contract.json").getContentAsString(StandardCharsets.UTF_8));
    }

    private static String configuredDestination() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        return properties == null ? null : properties.getProperty("notification.email.destination");
    }
}

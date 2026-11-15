package com.awesome.notification.events;

import com.awesome.notification.client.FeedEvent;
import com.awesome.notification.support.AbstractFlowTest;
import com.awesome.notification.support.EmbeddedEventBroker;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commerce emails through the broker transport against an embedded Artemis broker with the commerce event
 * topology (docs/EVENT_BROKER_CONTRACT.md in awesome-localstack): delivery, duplicate deliveries and a
 * poison message that ends in the dead-letter queue without blocking the queue.
 */
@Import(EmbeddedEventBroker.class)
@TestPropertySource(properties = {
        "notification.events.transport=broker",
        "spring.artemis.mode=embedded",
        "spring.artemis.embedded.enabled=true",
        "spring.artemis.embedded.persistent=false"})
class EventBrokerFlowTest extends AbstractFlowTest {

    private static final Duration DEADLINE = Duration.ofSeconds(15);

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private DefaultMessageListenerContainer eventBrokerListenerContainer;

    private JmsTemplate sender;

    @BeforeEach
    void sender() {
        sender = new JmsTemplate(connectionFactory);
        sender.setPubSubDomain(true);
    }

    @Test
    void theListenerRunsOnThisServicesQueueInsteadOfThePoller() {
        assertThat(eventBrokerListenerContainer.isRunning()).isTrue();
        assertThat(eventBrokerListenerContainer.getDestinationName())
                .isEqualTo("awesome.commerce.events::awesome.commerce.events.notification-service");
    }

    @Test
    void aRequestedEmailIsQueuedOnceEvenWhenTheEventIsDeliveredTwice() {
        // given
        String recipient = "broker-" + UUID.randomUUID() + "@example.com";
        FeedEvent event = giftCardIssued(recipient);
        long cursorBefore = poller.cursor();

        // when
        publish(event);
        publish(event);

        // then
        await(() -> processedEvents.existsById(event.id()));
        await(() -> emailRepository.findAll().stream().anyMatch(email -> recipient.equals(email.getRecipientEmail())));
        assertThat(emailRepository.findAll()).filteredOn(email -> recipient.equals(email.getRecipientEmail())).hasSize(1);
        assertThat(poller.cursor()).as("broker deliveries leave the feed cursor alone").isEqualTo(cursorBefore);
    }

    @Test
    void aPoisonMessageEndsInTheDeadLetterQueueAndTheNextEventIsStillHandled() throws Exception {
        // given
        FeedEvent good = giftCardIssued("after-poison-" + UUID.randomUUID() + "@example.com");

        // when
        sender.send(EmbeddedEventBroker.ADDRESS, session -> {
            TextMessage message = session.createTextMessage("{\"not\":\"an event\"");
            message.setStringProperty("type", NotificationRequestedHandler.EVENT_TYPE);
            return message;
        });
        publish(good);

        // then
        await(() -> processedEvents.existsById(good.id()));
        JmsTemplate receiver = new JmsTemplate(connectionFactory);
        receiver.setReceiveTimeout(DEADLINE.toMillis());
        Message dead = receiver.receive(EmbeddedEventBroker.DEAD_LETTER_QUEUE);
        assertThat(dead).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) dead).getText()).isEqualTo("{\"not\":\"an event\"");
        assertThat(dead.getStringProperty("_AMQ_ORIG_QUEUE")).isEqualTo(EmbeddedEventBroker.QUEUE);
    }

    private FeedEvent giftCardIssued(String recipient) {
        String data = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"template\":\"GIFT_CARD_ISSUED\",\"recipientEmail\":\""
                + recipient + "\",\"recipientName\":\"Jane\",\"properties\":{\"code\":\"GC-1\",\"amount\":\"20.00\"}}";
        return new FeedEvent(System.nanoTime(), UUID.randomUUID(), NotificationRequestedHandler.EVENT_TYPE,
                Instant.now().toString(), 42L, "client", jsonMapper.readTree(data), null, null, null);
    }

    private void publish(FeedEvent event) {
        String body = jsonMapper.writeValueAsString(event);
        sender.send(EmbeddedEventBroker.ADDRESS, session -> {
            TextMessage message = session.createTextMessage(body);
            message.setStringProperty("type", event.type());
            message.setStringProperty("JMSXGroupID", "order-" + event.orderId());
            return message;
        });
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within " + DEADLINE);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}

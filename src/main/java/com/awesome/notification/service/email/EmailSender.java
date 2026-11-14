package com.awesome.notification.service.email;

import com.awesome.notification.dto.email.EmailDto;
import com.awesome.notification.jms.EmailTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/**
 * Hands a rendered email to the configured transport: the JMS queue (the {@code consumer} delivers it
 * to Mailpit) or the in-memory local outbox. A JMS failure is thrown, so the email is retried.
 */
@Slf4j
@Component
public class EmailSender {

    private final JmsTemplate jmsTemplate;
    private final LocalEmailOutbox localEmailOutbox;
    private final EmailTransport transportType;
    private final String destination;

    public EmailSender(JmsTemplate jmsTemplate, LocalEmailOutbox localEmailOutbox,
                       NotificationEmailProperties properties) {
        this.jmsTemplate = jmsTemplate;
        this.localEmailOutbox = localEmailOutbox;
        this.transportType = properties.transportType();
        this.destination = properties.getDestination();
    }

    public EmailTransport transport() {
        return transportType;
    }

    public void send(EmailDto email) {
        if (transportType == EmailTransport.JMS) {
            jmsTemplate.convertAndSend(destination, email);
            return;
        }
        log.info("Local email outbox - not sending {} email to {}", email.getTemplate(), destination);
        localEmailOutbox.store(destination, email);
    }
}

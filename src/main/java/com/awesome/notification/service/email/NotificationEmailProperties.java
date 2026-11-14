package com.awesome.notification.service.email;

import com.awesome.notification.jms.EmailTransport;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Commerce email delivery ({@code notification.email.*}). */
@ConfigurationProperties(prefix = "notification.email")
@Getter
@Setter
public class NotificationEmailProperties {

    /** {@code local-outbox} (in memory, default) or {@code jms} (Artemis queue {@link #destination}). */
    private String transport = "local-outbox";
    /** JMS queue the consumer reads, the backend's {@code activemq.destination}. */
    private String destination = "email";
    /** Attempts before an email is FAILED (ADR 3 outbox convention). */
    private int maxAttempts = 10;
    /** Delay after the first failure, doubling per attempt up to {@link #maxBackoff}. */
    private Duration initialBackoff = Duration.ofSeconds(30);
    private Duration maxBackoff = Duration.ofMinutes(30);
    /** Send new emails on a background thread (true) or in the polling thread (tests). */
    private boolean asyncDispatch = true;
    private Retry retry = new Retry();
    private LocalOutbox localOutbox = new LocalOutbox();

    public EmailTransport transportType() {
        return EmailTransport.from(transport);
    }

    @Getter
    @Setter
    public static class Retry {
        private boolean enabled = true;
        private Duration interval = Duration.ofSeconds(30);
        private Duration initialDelay = Duration.ofSeconds(30);
        private int batchSize = 20;
    }

    @Getter
    @Setter
    public static class LocalOutbox {
        /** Emails kept in memory (oldest dropped first), so the 160 MiB container never fills up. */
        private int capacity = 500;
        /** When set, GET/DELETE of the outbox also need this value in X-Local-Outbox-Key. */
        private String accessKey = "";
    }
}

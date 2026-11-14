package com.awesome.notification.jms;

import jakarta.jms.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MessageConverter;
import tools.jackson.databind.json.JsonMapper;

/**
 * JMS producer of commerce emails, configured like the backend's (transacted session, JSON text
 * messages). Always wired, also with the local outbox: the native image fixes the bean graph at build
 * time, and the Artemis connection factory connects only on the first send
 * ({@code SPRING_ARTEMIS_BROKER_URL}, {@code _USER}, {@code _PASSWORD}).
 */
@Configuration(proxyBeanMethods = false)
public class EmailJmsConfig {

    @Bean
    MessageConverter emailMessageConverter() {
        // A dedicated mapper: profile-specific Jackson settings (indentation) never reach the queue.
        return new JsonTextMessageConverter(JsonMapper.builder().build());
    }

    @Bean
    JmsTemplate jmsTemplate(ConnectionFactory connectionFactory, MessageConverter emailMessageConverter) {
        JmsTemplate jmsTemplate = new JmsTemplate(connectionFactory);
        jmsTemplate.setMessageConverter(emailMessageConverter);
        jmsTemplate.setSessionTransacted(true);
        return jmsTemplate;
    }
}

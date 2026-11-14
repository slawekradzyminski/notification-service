package com.awesome.notification.jms;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.jms.support.converter.MessageConverter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes emails as the backend does: a JSON {@code TextMessage} whose {@code _awesome_} property is
 * the backend's email type id, which the {@code jms-email-consumer} maps to its email class (its
 * contract file {@code email-jms-contract.json}).
 */
public class JsonTextMessageConverter implements MessageConverter {

    public static final String TYPE_ID_PROPERTY = "_awesome_";
    /** The type id the backend sends (its EmailDto class name); the consumer resolves it. */
    public static final String EMAIL_TYPE_ID = "com.awesome.testing.dto.email.EmailDto";

    private final ObjectMapper objectMapper;

    public JsonTextMessageConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Message toMessage(Object object, Session session) throws JMSException {
        TextMessage message = session.createTextMessage(serialize(object));
        message.setStringProperty(TYPE_ID_PROPERTY, EMAIL_TYPE_ID);
        return message;
    }

    @Override
    public Object fromMessage(Message message) throws JMSException {
        if (!(message instanceof TextMessage textMessage)) {
            throw new MessageConversionException("Only TextMessage is supported");
        }
        return textMessage.getText();
    }

    private String serialize(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JacksonException ex) {
            throw new MessageConversionException("Failed to serialize JMS payload", ex);
        }
    }
}

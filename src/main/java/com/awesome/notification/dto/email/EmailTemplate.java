package com.awesome.notification.dto.email;

/**
 * The commerce email templates the notification-service renders: the backend's {@code EmailTemplate}
 * without the account ones (GENERIC, PASSWORD_RESET_REQUESTED, PASSWORD_RESET_CONFIRMED), which stay in
 * the backend. The names travel with every email as metadata; the JMS consumer delivers unknown
 * templates as GENERIC.
 */
public enum EmailTemplate {
    BACK_IN_STOCK,
    ORDER_SHIPPED,
    ORDER_DELIVERED,
    RETURN_UPDATED,
    PAYMENT_RECEIVED,
    PAYMENT_FAILED,
    SHIPMENT_OUT_FOR_DELIVERY,
    SHIPMENT_DELIVERY_FAILED,
    LOYALTY_POINTS_EARNED,
    GIFT_CARD_ISSUED
}

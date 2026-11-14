package com.awesome.notification.config;

import com.awesome.notification.controller.email.LocalNotificationOutboxController;
import io.swagger.v3.oas.models.OpenAPI;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/** Keeps the local outbox out of the published contract wherever it is inactive (JMS transport). */
@Component
@RequiredArgsConstructor
public class LocalOutboxOpenApiCustomizer implements OpenApiCustomizer {

    private final LocalNotificationOutboxController controller;

    @Override
    public void customise(OpenAPI openApi) {
        if (!controller.isActive() && openApi.getPaths() != null) {
            openApi.getPaths().remove(LocalNotificationOutboxController.PATH);
        }
    }
}

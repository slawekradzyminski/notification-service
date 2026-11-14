package com.awesome.notification.config;

import com.awesome.notification.support.AbstractFlowTest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiDocumentationTest extends AbstractFlowTest {

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    void everyControllerEndpointHasSummaryDescriptionAndResponses() {
        List<String> violations = new ArrayList<>();
        requestMappingHandlerMapping.getHandlerMethods().forEach((mapping, handler) -> validate(mapping, handler, violations));

        assertThat(violations).withFailMessage("Missing OpenAPI annotations:\n%s", String.join("\n", violations)).isEmpty();
    }

    @Test
    void apiDocsArePublicAndDescribeThePublicPathsWithBearerAuth() {
        ResponseEntity<String> docs = executeGet("/v3/api-docs", getJsonOnlyHeaders(), String.class);
        JsonNode root = jsonMapper.readTree(docs.getBody());

        assertThat(docs.getStatusCode().value()).isEqualTo(200);
        assertThat(root.at("/components/securitySchemes/bearerAuth/scheme").asString()).isEqualTo("bearer");
        assertThat(root.at("/components/securitySchemes/bearerAuth/bearerFormat").asString()).isEqualTo("JWT");
        // The webhook paths of backend 3.20.1, the email list and the local outbox (local-outbox transport in tests).
        assertThat(root.get("paths").propertyNames()).containsExactlyInAnyOrder(
                "/api/v1/webhooks",
                "/api/v1/webhooks/event-types",
                "/api/v1/webhooks/{id}",
                "/api/v1/webhooks/{id}/rotate-secret",
                "/api/v1/webhooks/{id}/test",
                "/api/v1/webhooks/{id}/deliveries",
                "/api/v1/webhooks/{id}/deliveries/{deliveryId}",
                "/api/v1/webhooks/{id}/deliveries/{deliveryId}/redeliver",
                "/api/v1/webhook-inboxes",
                "/api/v1/webhook-inboxes/{token}",
                "/api/v1/webhook-inboxes/{token}/requests",
                "/api/v1/admin/webhooks",
                "/api/v1/notifications/emails",
                "/api/v1/local/notifications/outbox");
        assertThat(root.at("/paths/~1api~1v1~1webhooks/post/responses").propertyNames())
                .contains("201", "400", "401", "409");
        assertThat(root.at("/paths/~1api~1v1~1webhooks~1{id}~1test/post/responses").propertyNames())
                .contains("201", "401", "403", "404", "409", "429");
        assertThat(root.at("/paths/~1api~1v1~1notifications~1emails/get/responses").propertyNames())
                .contains("200", "400", "401", "403");
        assertThat(root.at("/paths/~1api~1v1~1webhooks/post/security/0").has("bearerAuth")).isTrue();
        assertThat(docs.getBody()).doesNotContain("/internal/");
    }

    @Test
    void everyResponseDocumentsTheRequestIdHeader() {
        JsonNode root = jsonMapper.readTree(executeGet("/v3/api-docs", getJsonOnlyHeaders(), String.class).getBody());

        assertThat(root.at("/components/headers/X-Request-Id/schema/maxLength").asInt()).isEqualTo(128);
        List<String> missing = new ArrayList<>();
        root.get("paths").properties().forEach(path -> path.getValue().properties().forEach(operation ->
                operation.getValue().get("responses").properties().forEach(response -> {
                    if (!"#/components/headers/X-Request-Id".equals(
                            response.getValue().at("/headers/X-Request-Id/$ref").asString())) {
                        missing.add(path.getKey() + " " + operation.getKey() + " " + response.getKey());
                    }
                })));
        assertThat(missing).isEmpty();
        assertThat(root.at("/paths/~1api~1v1~1webhooks~1{id}/get/responses").size()).isGreaterThan(2);
    }

    @Test
    void healthAndPrometheusArePublicButOtherActuatorEndpointsAreNot() {
        assertThat(executeGet("/actuator/health", getJsonOnlyHeaders(), String.class).getStatusCode().value())
                .isEqualTo(200);
        ResponseEntity<String> prometheus = executeGet("/actuator/prometheus", new org.springframework.http.HttpHeaders(),
                String.class);
        assertThat(prometheus.getStatusCode().value()).isEqualTo(200);
        assertThat(prometheus.getBody()).contains("notification_outbox_pending");
        assertThat(executeGet("/actuator/env", getJsonOnlyHeaders(), String.class).getStatusCode().value())
                .isEqualTo(401);
    }

    private static void validate(RequestMappingInfo mapping, HandlerMethod handler, List<String> violations) {
        if (!handler.getBeanType().getPackageName().startsWith("com.awesome.notification.controller")) {
            return;
        }
        String endpoint = mapping.getPatternValues() + " -> " + handler.getBeanType().getSimpleName() + "#"
                + handler.getMethod().getName();
        Operation operation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Operation.class);
        if (operation == null || operation.summary().isBlank() || operation.description().isBlank()) {
            violations.add(endpoint + " needs @Operation with summary and description");
        }
        if (AnnotatedElementUtils.findMergedRepeatableAnnotations(handler.getMethod(), ApiResponse.class).isEmpty()) {
            violations.add(endpoint + " needs @ApiResponse entries");
        }
    }
}

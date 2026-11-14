package com.awesome.notification.config;

import com.awesome.notification.correlation.Correlation;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
    private static final String REQUEST_ID_HEADER_REF = "#/components/headers/" + Correlation.REQUEST_ID_HEADER;

    @Bean
    OpenAPI notificationOpenApi() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .name(BEARER_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addHeaders(Correlation.REQUEST_ID_HEADER, new Header()
                                .description("The request's correlation id: a valid incoming X-Request-Id "
                                        + "(1-128 characters of [A-Za-z0-9._-]) or a generated one")
                                .schema(new StringSchema().maxLength(128))))
                .info(new Info()
                        .title("Notification Service API")
                        .version("1.0")
                        .description("User webhooks (subscriptions, signed deliveries, inboxes) and commerce emails. "
                                + "Uses the access tokens issued by the backend. Requests may carry X-Request-Id and a "
                                + "W3C traceparent; every response answers X-Request-Id."));
    }

    /** Documents the {@code X-Request-Id} header on every response of every operation. */
    @Bean
    OpenApiCustomizer requestIdResponseHeader() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                if (operation.getResponses() != null) {
                    operation.getResponses().values().forEach(response -> response.addHeaderObject(
                            Correlation.REQUEST_ID_HEADER, new Header().$ref(REQUEST_ID_HEADER_REF)));
                }
            }));
        };
    }
}

package com.awesome.notification.client;

import com.awesome.notification.controller.exception.CustomException;
import com.awesome.notification.correlation.CorrelationInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link BackendClient} over HTTP with Spring's {@link RestClient}. Every call carries the service
 * token and {@code X-Request-Id}; the hand-written child {@code traceparent} is added only while tracing
 * is off (with tracing on, the instrumented client propagates the current span). Feed pages and the
 * webhook export are read as text and parsed with {@link FeedJson}, which keeps exact decimals.
 */
@Slf4j
public class RestBackendClient implements BackendClient {

    public static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final RestClient restClient;

    public RestBackendClient(RestClient.Builder builder, String baseUrl, String serviceToken) {
        this(builder, baseUrl, serviceToken, true);
    }

    public RestBackendClient(RestClient.Builder builder, String baseUrl, String serviceToken,
                             boolean handWrittenTraceparent) {
        RestClient.Builder configured = builder
                .baseUrl(baseUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor(new CorrelationInterceptor(handWrittenTraceparent))
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw BackendErrorMapper.fromResponse(
                            response.getStatusCode(), StreamUtils.copyToByteArray(response.getBody()));
                });
        if (serviceToken == null || serviceToken.isBlank()) {
            // Blank token = the notification integration is disabled; the backend will refuse our calls.
            log.warn("notification.service-token is blank: backend calls are sent without X-Service-Token");
        } else {
            configured.defaultHeader(SERVICE_TOKEN_HEADER, serviceToken);
        }
        this.restClient = configured.build();
    }

    @Override
    public JwkSet jwks() {
        return required(() -> restClient.get()
                .uri("/internal/v1/jwks.json")
                .retrieve()
                .body(JwkSet.class));
    }

    @Override
    public EventFeedPage events(long after, int limit, List<String> types) {
        String body = required(() -> restClient.get()
                .uri(uri -> uri.path("/internal/v1/events")
                        .queryParam("after", after)
                        .queryParam("limit", limit)
                        .queryParam("types", String.join(",", types))
                        .build())
                .retrieve()
                .body(String.class));
        return parse(body, EventFeedPage.class);
    }

    @Override
    public Optional<WebhookExport> exportWebhooks() {
        String body;
        try {
            body = required(() -> restClient.get()
                    .uri("/internal/v1/webhooks/export")
                    .retrieve()
                    .body(String.class));
        } catch (BackendException e) {
            if (e.getBackendStatus() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw e;
        }
        return Optional.of(parse(body, WebhookExport.class));
    }

    private static <T> T parse(String body, Class<T> type) {
        try {
            return FeedJson.MAPPER.readValue(body, type);
        } catch (JacksonException e) {
            throw BackendErrorMapper.unavailable(e);
        }
    }

    private static <T> T required(Supplier<T> request) {
        T body = call(request);
        if (body == null) {
            throw BackendErrorMapper.unavailable(new IllegalStateException("Backend returned an empty body"));
        }
        return body;
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (CustomException e) {
            throw e;
        } catch (RestClientException e) {
            throw BackendErrorMapper.unavailable(e);
        }
    }
}

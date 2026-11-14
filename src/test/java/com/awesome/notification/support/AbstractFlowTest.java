package com.awesome.notification.support;

import com.awesome.notification.events.EventFeedPoller;
import com.awesome.notification.repository.EventCursorRepository;
import com.awesome.notification.repository.NotificationEmailRepository;
import com.awesome.notification.repository.ProcessedEventRepository;
import com.awesome.notification.repository.webhook.WebhookDeliveryRepository;
import com.awesome.notification.repository.webhook.WebhookInboxRepository;
import com.awesome.notification.repository.webhook.WebhookInboxRequestRepository;
import com.awesome.notification.repository.webhook.WebhookSubscriptionRepository;
import com.awesome.notification.service.email.LocalEmailOutbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Boots the whole service on H2 with the in-memory backend, stub DNS and a hand-moved clock, driven
 * over MockMvc. The request helpers mirror the backend's {@code DomainHelper}
 * ({@code executeGet/Post/Put/Delete} returning a {@link ResponseEntity}), so the backend's webhook
 * endpoint tests port with their assertions unchanged.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestBackendConfig.class)
public abstract class AbstractFlowTest {

    private static final AtomicInteger USERS = new AtomicInteger();

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JsonMapper jsonMapper;
    @Autowired protected FakeBackend backend;
    @Autowired protected MutableClock clock;
    @Autowired protected EventFeedPoller poller;
    @Autowired protected EventCursorRepository eventCursors;
    @Autowired protected ProcessedEventRepository processedEvents;
    @Autowired protected WebhookSubscriptionRepository subscriptionRepository;
    @Autowired protected WebhookDeliveryRepository deliveryRepository;
    @Autowired protected WebhookInboxRepository inboxRepository;
    @Autowired protected WebhookInboxRequestRepository inboxRequestRepository;
    @Autowired protected NotificationEmailRepository emailRepository;
    @Autowired protected LocalEmailOutbox localEmailOutbox;

    @BeforeEach
    void resetBackend() {
        backend.reset();
        clock.reset();
        localEmailOutbox.clear();
    }

    @AfterEach
    void cleanDatabase() {
        deliveryRepository.deleteAll();
        subscriptionRepository.deleteAll();
        inboxRequestRepository.deleteAll();
        inboxRepository.deleteAll();
        emailRepository.deleteAll();
        processedEvents.deleteAll();
        eventCursors.deleteAll();
    }

    /** A fresh username, so tests never see each other's subscriptions or limits. */
    protected static String randomUsername() {
        return "user" + USERS.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String getToken(String username, String... roles) {
        return TestTokens.token(username, roles);
    }

    protected static HttpHeaders getHeadersWith(String token) {
        HttpHeaders headers = getJsonOnlyHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    protected static HttpHeaders getJsonOnlyHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    protected <T> ResponseEntity<T> executeGet(String url, HttpHeaders headers, Class<T> type) {
        return exchange(MockMvcRequestBuilders.get(url), null, headers, jsonMapper.constructType(type));
    }

    protected <T> ResponseEntity<T> executeGet(String url, HttpHeaders headers, ParameterizedTypeReference<T> type) {
        return exchange(MockMvcRequestBuilders.get(url), null, headers, jsonMapper.constructType(type.getType()));
    }

    protected <T> ResponseEntity<T> executePost(String url, Object body, HttpHeaders headers, Class<T> type) {
        return exchange(MockMvcRequestBuilders.post(url), body, headers, jsonMapper.constructType(type));
    }

    protected <T> ResponseEntity<T> executePost(String url, Object body, HttpHeaders headers,
                                                ParameterizedTypeReference<T> type) {
        return exchange(MockMvcRequestBuilders.post(url), body, headers, jsonMapper.constructType(type.getType()));
    }

    protected <T> ResponseEntity<T> executePut(String url, Object body, HttpHeaders headers, Class<T> type) {
        return exchange(MockMvcRequestBuilders.put(url), body, headers, jsonMapper.constructType(type));
    }

    protected <T> ResponseEntity<T> executeDelete(String url, HttpHeaders headers, Class<T> type) {
        return exchange(MockMvcRequestBuilders.delete(url), null, headers, jsonMapper.constructType(type));
    }

    private <T> ResponseEntity<T> exchange(MockHttpServletRequestBuilder builder, Object body, HttpHeaders headers,
                                           JavaType type) {
        builder.headers(headers);
        if (body != null) {
            builder.content(body instanceof String raw ? raw : jsonMapper.writeValueAsString(body));
        }
        try {
            MockHttpServletResponse response = mockMvc.perform(builder).andReturn().getResponse();
            String content = response.getContentAsString(StandardCharsets.UTF_8);
            HttpHeaders responseHeaders = new HttpHeaders();
            response.getHeaderNames().forEach(name -> responseHeaders.addAll(name, response.getHeaders(name)));
            T parsed = content.isEmpty() || type.getRawClass() == Void.class ? null
                    : type.getRawClass() == String.class ? AbstractFlowTest.<T>castString(content)
                    : jsonMapper.readValue(content, type);
            return new ResponseEntity<>(parsed, responseHeaders, HttpStatusCode.valueOf(response.getStatus()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T castString(String content) {
        return (T) content;
    }

    /** Polls the feed once (as the scheduled poller would) and returns how many events were handled. */
    protected int poll() {
        return poller.poll();
    }
}

package com.awesome.notification.config;

import com.awesome.notification.client.BackendClient;
import com.awesome.notification.client.RestBackendClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class BackendClientConfig {

    /** Shared JDK HTTP client for the internal API; closed with the application context. */
    @Bean(destroyMethod = "close")
    HttpClient backendHttpClient(@Value("${backend.connect-timeout:2s}") Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    BackendClient backendClient(
            RestClient.Builder builder,
            HttpClient backendHttpClient,
            @Value("${backend.base-url}") String baseUrl,
            @Value("${notification.service-token:}") String serviceToken,
            @Value("${backend.read-timeout:10s}") Duration readTimeout,
            @Value("${tracing.enabled:false}") boolean tracingEnabled) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(backendHttpClient);
        requestFactory.setReadTimeout(readTimeout);
        // With tracing on, the instrumented RestClient propagates the current span as traceparent.
        return new RestBackendClient(builder.requestFactory(requestFactory), baseUrl, serviceToken, !tracingEnabled);
    }
}

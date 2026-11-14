package com.awesome.notification.correlation;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Early filter of every request: keeps a valid incoming {@code X-Request-Id} (otherwise generates
 * one) and puts it, the trace id and this hop's span id into the MDC for the request, answers the
 * request id in the {@code X-Request-Id} response header and restores the MDC afterwards. It runs
 * right after the HTTP server observation filter, so when the request has a tracing span (whose parent
 * is a valid incoming {@code traceparent}) the MDC carries that span's ids; without one it takes the
 * trace id of a valid incoming {@code traceparent} or starts a new trace. It runs before Spring
 * Security, so 401/403 answers carry the header and their log lines the ids.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CorrelationFilter extends OncePerRequestFilter {

    private final ObjectProvider<Tracer> tracer;

    public CorrelationFilter(ObjectProvider<Tracer> tracer) {
        super();
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = Correlation.validRequestId(request.getHeader(Correlation.REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = Correlation.newRequestId();
        }
        response.setHeader(Correlation.REQUEST_ID_HEADER, requestId);
        Span span = currentSpan();
        if (span != null) {
            try (Correlation.Scope ignored = Correlation.open(requestId, span.context().traceId(),
                    span.context().spanId())) {
                chain.doFilter(request, response);
            }
            return;
        }
        Correlation.TraceParent parent = Correlation.parseTraceparent(
                request.getHeader(Correlation.TRACEPARENT_HEADER));
        String traceId = parent == null ? Correlation.newTraceId() : parent.traceId();
        try (Correlation.Scope ignored = Correlation.open(requestId, traceId)) {
            chain.doFilter(request, response);
        }
    }

    private Span currentSpan() {
        Tracer current = tracer == null ? null : tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        if (span == null || Correlation.validTraceId(span.context().traceId()) == null
                || Correlation.validSpanId(span.context().spanId()) == null) {
            return null;
        }
        return span;
    }
}

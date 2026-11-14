package com.awesome.notification.tracing;

import com.awesome.notification.correlation.Correlation;
import io.micrometer.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import java.util.Set;

/**
 * Runtime switch for OpenTelemetry tracing (contract section 4), the same approach as warehouse-service.
 *
 * <p>Spring Boot's own switch ({@code management.tracing.export.enabled}) is a bean condition, and
 * Spring AOT fixes bean conditions when the native image is built. So the tracing beans (OTLP HTTP
 * exporter, W3C propagator, observation handlers) are always present, and {@code tracing.enabled}
 * ({@code TRACING_ENABLED}) is read at runtime by these beans:
 * <ul>
 *   <li>the sampler records nothing while tracing is off (not even requests whose incoming
 *       {@code traceparent} is sampled), so nothing is exported; while on it is parent-based with
 *       {@code TRACING_SAMPLING_PROBABILITY} for new traces;</li>
 *   <li>Micrometer's MDC listener is silenced: {@code CorrelationFilter} and {@link Spans} own the
 *       {@code traceId}/{@code spanId} MDC keys;</li>
 *   <li>the idle scheduled runs (feed poll, retry jobs, import) are not recorded as root spans (they
 *       would add an empty trace every few seconds); the event, delivery and email spans they start
 *       have their own parents and are recorded.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

    /** Scheduled methods ({@code SimpleClassName.method}) whose runs do not start a recorded trace. */
    static final Set<String> UNTRACED_RUNS = Set.of("EventFeedPoller.scheduledPoll", "WebhookRetryJob.scheduledRetry",
            "NotificationEmailRetryJob.scheduledRetry", "WebhookImporter.scheduledImport");

    @Bean
    Sampler otelSampler(@Value("${tracing.enabled:false}") boolean enabled,
                        @Value("${management.tracing.sampling.probability:1.0}") double probability) {
        return sampler(enabled, probability);
    }

    static Sampler sampler(boolean enabled, double probability) {
        return enabled ? Sampler.parentBased(Sampler.traceIdRatioBased(probability)) : Sampler.alwaysOff();
    }

    @Bean
    Slf4JEventListener notificationSlf4JEventListener() {
        return new SilentMdcListener();
    }

    @Bean
    @Order(MicrometerTracingAutoConfiguration.DEFAULT_TRACING_OBSERVATION_HANDLER_ORDER)
    DefaultTracingObservationHandler notificationTracingObservationHandler(Tracer tracer) {
        return new UntracedRunsHandler(tracer);
    }

    /** Leaves the MDC to the correlation code. */
    static final class SilentMdcListener extends Slf4JEventListener {

        @Override
        public void onEvent(Object event) {
            // The trace and span ids in the MDC are set by CorrelationFilter and Spans.
        }
    }

    /**
     * Gives the runs listed in {@link #UNTRACED_RUNS} a span under an unsampled parent, so the run
     * and the calls it makes are not recorded; every other observation is handled as usual.
     */
    static final class UntracedRunsHandler extends DefaultTracingObservationHandler {

        UntracedRunsHandler(Tracer tracer) {
            super(tracer);
        }

        @Override
        public void onStart(Observation.Context context) {
            if (!isUntracedRun(context)) {
                super.onStart(context);
                return;
            }
            TraceContext unsampled = getTracer().traceContextBuilder()
                    .traceId(Correlation.newTraceId())
                    .spanId(Correlation.newSpanId())
                    .sampled(false)
                    .build();
            Span span = getTracer().spanBuilder().setParent(unsampled).start();
            getTracingContext(context).setSpan(span);
        }

        static boolean isUntracedRun(Observation.Context context) {
            return context instanceof ScheduledTaskObservationContext task
                    && UNTRACED_RUNS.contains(task.getTargetClass().getSimpleName() + "." + task.getMethod().getName());
        }
    }
}

package com.awesome.notification.service.email;

import com.awesome.notification.dto.email.EmailDto;
import com.awesome.notification.dto.email.EmailTemplate;
import com.awesome.notification.entity.NotificationEmailEntity;
import com.awesome.notification.jms.EmailTransport;
import com.awesome.notification.tracing.Spans;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.jms.UncategorizedJmsException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEmailDispatcherTest {

    private static final UUID ID = UUID.fromString("0b8f4f0e-3f43-4b8e-9d55-4a3f1c1f6a10");
    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    private final NotificationEmailOutbox outbox = mock(NotificationEmailOutbox.class);
    private final EmailSender sender = mock(EmailSender.class);
    private final NotificationEmailProperties properties = new NotificationEmailProperties();
    private NotificationEmailDispatcher dispatcher;
    private final EmailDto email = EmailDto.builder().to("a@example.com").subject("S").message("M")
            .template(EmailTemplate.ORDER_DELIVERED).build();

    @BeforeEach
    void setUp() {
        properties.setAsyncDispatch(false);
        dispatcher = new NotificationEmailDispatcher(outbox, sender, properties, Spans.disabled());
        when(sender.transport()).thenReturn(EmailTransport.LOCAL_OUTBOX);
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
        MDC.clear();
    }

    @Test
    void sendsAClaimedEmailUnderTheStoredCorrelationAndRecordsSuccess() {
        // given
        when(outbox.claim(ID)).thenReturn(Optional.of(new NotificationEmailOutbox.Attempt(ID, email, "req-1",
                "00-" + TRACE + "-b7ad6b7169203331-01")));
        NotificationEmailEntity recorded = new NotificationEmailEntity();
        when(outbox.recordAttempt(ID, null)).thenReturn(recorded);
        Map<String, String> mdc = new HashMap<>();
        doAnswer(invocation -> {
            mdc.putAll(MDC.getCopyOfContextMap());
            return null;
        }).when(sender).send(email);

        // when
        Optional<NotificationEmailEntity> result = dispatcher.dispatch(ID);

        // then
        assertThat(result).containsSame(recorded);
        assertThat(mdc).containsEntry("requestId", "req-1").containsEntry("traceId", TRACE);
        assertThat(mdc.get("spanId")).hasSize(16).isNotEqualTo("b7ad6b7169203331");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void anEmailWithoutStoredIdsIsSentUnderFreshOnes() {
        when(outbox.claim(ID)).thenReturn(Optional.of(new NotificationEmailOutbox.Attempt(ID, email, null, "garbage")));
        Map<String, String> mdc = new HashMap<>();
        doAnswer(invocation -> {
            mdc.putAll(MDC.getCopyOfContextMap());
            return null;
        }).when(sender).send(email);

        dispatcher.dispatch(ID);

        assertThat(mdc.get("requestId")).hasSize(36);
        assertThat(mdc.get("traceId")).hasSize(32);
    }

    @Test
    void aTransportFailureIsRecordedWithItsReason() {
        when(outbox.claim(ID)).thenReturn(Optional.of(new NotificationEmailOutbox.Attempt(ID, email, null, null)));
        doThrow(new UncategorizedJmsException("broker down")).when(sender).send(email);

        dispatcher.dispatch(ID);

        verify(outbox).recordAttempt(ID, "UncategorizedJmsException: broker down");
    }

    @Test
    void aFailureWithoutMessageIsRecordedByItsType() {
        when(outbox.claim(ID)).thenReturn(Optional.of(new NotificationEmailOutbox.Attempt(ID, email, null, null)));
        doThrow(new IllegalStateException()).when(sender).send(email);

        dispatcher.dispatch(ID);

        verify(outbox).recordAttempt(ID, "IllegalStateException");
    }

    @Test
    void nothingHappensWhenTheEmailCannotBeClaimed() {
        when(outbox.claim(ID)).thenReturn(Optional.empty());

        assertThat(dispatcher.dispatch(ID)).isEmpty();
        verify(sender, never()).send(any());
        verify(outbox, never()).recordAttempt(any(), any());
    }

    @Test
    void unexpectedErrorsAreSwallowed() {
        when(outbox.claim(ID)).thenThrow(new IllegalStateException("database down"));
        assertThat(dispatcher.dispatch(ID)).isEmpty();
    }

    @Test
    void dispatchAllRunsInlineOrOnTheBackgroundThread() {
        UUID inline = UUID.randomUUID();
        UUID background = UUID.randomUUID();
        when(outbox.claim(any())).thenReturn(Optional.empty());

        dispatcher.dispatchAll(List.of(inline));
        verify(outbox).claim(inline);
        properties.setAsyncDispatch(true);
        dispatcher.dispatchAll(List.of(background));

        verify(outbox, timeout(2_000)).claim(background);
    }

    @Test
    void theRetryJobAttemptsEveryDueEmailWhenEnabled() {
        // given
        UUID sent = UUID.randomUUID();
        UUID held = UUID.randomUUID();
        when(outbox.dueIds(20)).thenReturn(List.of(sent, held));
        when(outbox.claim(sent)).thenReturn(Optional.of(new NotificationEmailOutbox.Attempt(sent, email, null, null)));
        when(outbox.claim(held)).thenReturn(Optional.empty());
        when(outbox.recordAttempt(eq(sent), isNull())).thenReturn(new NotificationEmailEntity());
        NotificationEmailRetryJob job = new NotificationEmailRetryJob(outbox, dispatcher, properties);

        // when / then
        assertThat(job.retryDueEmails()).isEqualTo(1);
        properties.getRetry().setEnabled(false);
        job.scheduledRetry();
        verify(outbox).dueIds(20);
        properties.getRetry().setEnabled(true);
        job.scheduledRetry();
        verify(outbox, timeout(1_000).times(2)).dueIds(20);
    }
}

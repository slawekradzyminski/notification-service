package com.awesome.notification.endpoints.email;

import com.awesome.notification.dto.ErrorDto;
import com.awesome.notification.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** With LOCAL_EMAIL_OUTBOX_ACCESS_KEY set, the local outbox also needs X-Local-Outbox-Key (like the backend's). */
@TestPropertySource(properties = "notification.email.local-outbox.access-key=outbox-key-1")
class LocalOutboxAccessKeyFlowTest extends AbstractFlowTest {

    @Test
    void theConfiguredKeyIsRequired() {
        // given
        String adminToken = getToken(randomUsername(), "ROLE_ADMIN");
        HttpHeaders withKey = getHeadersWith(adminToken);
        withKey.add("X-Local-Outbox-Key", "outbox-key-1");
        HttpHeaders wrongKey = getHeadersWith(adminToken);
        wrongKey.add("X-Local-Outbox-Key", "outbox-key-2");

        // when
        ResponseEntity<String> allowed = executeGet(NotificationEmailFlowTest.OUTBOX, withKey, String.class);
        ResponseEntity<ErrorDto> missing = executeGet(NotificationEmailFlowTest.OUTBOX, getHeadersWith(adminToken),
                ErrorDto.class);
        ResponseEntity<ErrorDto> wrong = executeGet(NotificationEmailFlowTest.OUTBOX, wrongKey, ErrorDto.class);
        ResponseEntity<ErrorDto> wrongClear = executeDelete(NotificationEmailFlowTest.OUTBOX, wrongKey, ErrorDto.class);

        // then
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowed.getBody()).isEqualTo("[]");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(wrong.getBody().getMessage()).isEqualTo("Access denied");
        assertThat(wrongClear.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

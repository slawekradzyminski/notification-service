package com.awesome.notification.dto.email;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEmailDtoTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "jane.doe@example.com,j***@example.com",
            "a@b.c,a***@b.c",
            "@example.com,***",
            "no-at-sign,***",
            "null,***"})
    void recipientsAreMaskedLikeTheBackendsEmailEvents(String email, String masked) {
        assertThat(NotificationEmailDto.maskRecipient(email)).isEqualTo(masked);
    }
}

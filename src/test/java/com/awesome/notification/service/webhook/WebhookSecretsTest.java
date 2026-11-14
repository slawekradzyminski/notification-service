package com.awesome.notification.service.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSecretsTest {

    private final WebhookSecrets secrets = new WebhookSecrets("unit-test-password", "0123456789abcdef");

    @Test
    void secretsAreWhsecPlus32RandomBytesAndRoundTripEncrypted() {
        String secret = secrets.generate();
        String stored = secrets.protect(secret);

        assertThat(secret).matches("whsec_[A-Za-z0-9_-]{43}").isNotEqualTo(secrets.generate());
        assertThat(stored).doesNotContain(secret).isNotEqualTo(secrets.protect(secret));
        assertThat(secrets.reveal(stored)).isEqualTo(secret);
        assertThat(new WebhookSecrets("unit-test-password", "0123456789abcdef").reveal(stored)).isEqualTo(secret);
        assertThat(secrets.randomToken(24)).matches("[A-Za-z0-9_-]{32}");
    }

    @Test
    void theKeyMustBeConfigured() {
        assertThatThrownBy(() -> new WebhookSecrets(" ", "0123456789abcdef")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOKS_SECRET_ENCRYPTION_PASSWORD");
        assertThatThrownBy(() -> new WebhookSecrets(null, "0123456789abcdef")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebhookSecrets("pw", "abc")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOKS_SECRET_ENCRYPTION_SALT");
        assertThatThrownBy(() -> new WebhookSecrets("pw", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebhookSecrets("pw", "0123456789abcdeg")).isInstanceOf(IllegalStateException.class);
    }
}

package com.awesome.notification.service.webhook;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates signing secrets ({@code whsec_} plus 32 random bytes, base64url) and keeps them encrypted
 * at rest (AES-256-GCM, key derived from {@code webhooks.secret-encryption.password} and
 * {@code .salt}). The backend used its MFA key; the notification-service has its own, and secrets
 * imported from the backend's export arrive in plain text and are encrypted with it.
 */
@Component
public class WebhookSecrets {

    static final String PREFIX = "whsec_";
    private static final int SECRET_BYTES = 32;

    private final TextEncryptor textEncryptor;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public WebhookSecrets(@Value("${webhooks.secret-encryption.password}") String password,
                          @Value("${webhooks.secret-encryption.salt}") String salt) {
        this(encryptor(password, salt));
    }

    WebhookSecrets(TextEncryptor encryptor) {
        this.textEncryptor = encryptor;
    }

    static TextEncryptor encryptor(String password, String salt) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("WEBHOOKS_SECRET_ENCRYPTION_PASSWORD must be configured");
        }
        if (salt == null || !salt.matches("([0-9a-fA-F]{2}){8,}")) {
            throw new IllegalStateException("WEBHOOKS_SECRET_ENCRYPTION_SALT must be at least 8 bytes in hexadecimal");
        }
        return Encryptors.delux(password, salt);
    }

    public String generate() {
        return PREFIX + randomToken(SECRET_BYTES);
    }

    public String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    public String protect(String secret) {
        return textEncryptor.encrypt(secret);
    }

    public String reveal(String stored) {
        return textEncryptor.decrypt(stored);
    }
}

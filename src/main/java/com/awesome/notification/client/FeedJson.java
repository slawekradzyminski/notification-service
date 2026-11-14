package com.awesome.notification.client;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mapper that reads feed pages and the webhook export. Decimals stay {@code BigDecimal} with
 * their scale ({@code 40.00} is not turned into {@code 40.0} or {@code 40}), so the webhook body
 * built from an event's {@code data} is byte-for-byte the one the backend built from the in-process
 * event. A dedicated mapper keeps profile-specific Jackson settings away from it.
 */
public final class FeedJson {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private FeedJson() {
    }
}

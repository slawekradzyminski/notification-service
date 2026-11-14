# notification-service

User webhooks and commerce emails for the Awesome Testing shop, moved out of the backend
(backend 3.21.0 / notification-service 1.0.0, distributed-lab contract section 2). Spring Boot 4.1,
Java 25, GraalVM native image (`-Xmx64m` in a 160 MiB container), port **4005**, internal only behind
the gateway. Like warehouse-service and payment-service it never receives calls from the backend: it
polls the backend's event feed (ADR 1 and ADR 3 in awesome-localstack).

| Concern | How |
| --- | --- |
| Authentication | Backend RS256 access tokens, verified with `GET /internal/v1/jwks.json` |
| Webhooks | The backend 3.20.1 API, unchanged: `/api/v1/webhooks/**`, `/api/v1/webhook-inboxes/**`, `GET /api/v1/admin/webhooks` |
| Webhook events | Feed types `order.created`, `order.paid`, `order.cancelled`, `order.reservation-expired`, `shipment.shipped`, `shipment.delivered`, `refund.booked`, `payment.failed` |
| Commerce emails | Feed type `notification.requested`, rendered here, sent to the JMS queue `email` or the in-memory local outbox |
| Database | Schema `notification` (`currentSchema=notification`), Flyway `V1__notification_schema.sql` |
| Metrics | `/actuator/prometheus`; gauge `notification_outbox_pending`, counter `notification_outbox_failed_total{kind}` |

## Public API

All endpoints need a backend Bearer token. Paths, JSON shapes, status codes, messages, limits,
signature, SSRF rules, retry policy and rate limits are those of backend 3.20.1 (its `docs/WEBHOOKS.md`),
ported with the backend's tests.

| Method and path | Result |
| --- | --- |
| `GET /api/v1/webhooks/event-types` | `200 [{name, description}]`: the eight feed types above plus `webhook.ping` |
| `GET /api/v1/webhooks` | `200 [WebhookSubscriptionDto]`, oldest first |
| `POST /api/v1/webhooks` `{url, events[], description?}` | `201` including `secret` (shown once); `400` invalid body, unknown type or rejected URL; `409` more than 5 |
| `GET` / `PUT` / `DELETE /api/v1/webhooks/{id}` | `200` / `200` / `204`; `403` another user's, `404` unknown |
| `POST /api/v1/webhooks/{id}/rotate-secret` | `200` with the new `secret` |
| `POST /api/v1/webhooks/{id}/test` | `201 WebhookDeliveryDto` (sends `webhook.ping` and waits); `409` disabled; `429` rate limit |
| `GET /api/v1/webhooks/{id}/deliveries?status&page&size` | `200 PageDto<WebhookDeliveryDto>`, newest first |
| `GET /api/v1/webhooks/{id}/deliveries/{deliveryId}` | `200`; `400` not a UUID; `404` unknown |
| `POST /api/v1/webhooks/{id}/deliveries/{deliveryId}/redeliver` | `201`; `409` disabled; `429` rate limit |
| `POST` / `GET /api/v1/webhook-inboxes` | `201 {token, url: "inbox://<token>", createdAt}` (`409` more than 3) / `200` list |
| `GET /api/v1/webhook-inboxes/{token}/requests` | `200`, newest first, last 50 kept; `404` unknown or another user's |
| `DELETE /api/v1/webhook-inboxes/{token}` | `204` |
| `GET /api/v1/admin/webhooks?username&page&size` | `200 PageDto<WebhookSubscriptionDto>` (`ROLE_ADMIN`, else `403`) |
| `GET /api/v1/notifications/emails?username&page&size` | `200 PageDto<NotificationEmailDto>`: the caller's commerce emails, newest first, recipient masked (`j***@example.com`); `ROLE_ADMIN` may pass `username` (others `403`) |
| `GET` / `DELETE /api/v1/local/notifications/outbox` | `200` captured emails / cleared (`ROLE_ADMIN`, plus `X-Local-Outbox-Key` when `LOCAL_EMAIL_OUTBOX_ACCESS_KEY` is set, else `403`); `404` and absent from OpenAPI with the `jms` transport |

`NotificationEmailDto`: `id, template, subject, status (QUEUED|SENT|FAILED), recipientMasked, attempts,
createdAt, updatedAt, sentAt, failureReason`. Errors are the backend's shapes: `{"message": ...}`,
field-to-message maps for invalid bodies, `{"error": ...}` for parameters that do not convert.
The OpenAPI document is `/v3/api-docs` (gateway: `/notification/v3/api-docs`).

## Webhooks from the event feed

Each webhook-eligible feed event of a user creates one `PENDING` delivery per active subscription of
that user listing the type, in the same local transaction that records the event in
`processed_events`, and is sent after the commit (then retried by `WebhookRetryJob`: 1, 2, 4… minutes,
8 attempts, auto-disable after 20 consecutive failures). The body is
`{"id":<event id>,"type":<wire name>,"createdAt":<occurredAt>,"data":{...sorted}}`, built from the feed
event, whose `data` is read with exact decimals: byte for byte the body backend 3.20.1 built from the
in-process event (`WebhookPayloadsTest` compares both algorithms). The delivery sends the backend
request's `X-Request-Id` and a child of its `traceparent`.

`WebhookHttpSender` connects only to the addresses `WebhookUrlPolicy` vetted (SSRF: no internal,
loopback, private, link-local, CGNAT, reserved or embedded-IPv4 addresses, no single-label or
`localhost`/`.local`/`.internal` hosts, ports 80/443 plus `WEBHOOKS_ALLOWED_PORTS`), and checks again
before every attempt (DNS rebinding).

### One-time import

On start (and every minute until one attempt completes), when `webhook_subscriptions` and
`webhook_inboxes` are both empty, the service calls `GET /internal/v1/webhooks/export` and inserts
the subscriptions with their ids (the identity restarts after the highest one) and the inboxes with
their tokens. Secrets arrive in plain text and are stored encrypted with this service's key
(`WEBHOOKS_SECRET_ENCRYPTION_PASSWORD` / `_SALT`). A 404 or an empty export ends the import.

## Commerce emails

`notification.requested` (`data: {requestId, template, recipientEmail, recipientName, properties}`) is
rendered with `CustomerEmailRenderer` (moved from the backend; templates `PAYMENT_RECEIVED`,
`PAYMENT_FAILED`, `BACK_IN_STOCK`, `ORDER_SHIPPED`, `ORDER_DELIVERED`, `RETURN_UPDATED`,
`SHIPMENT_OUT_FOR_DELIVERY`, `SHIPMENT_DELIVERY_FAILED`, `LOYALTY_POINTS_EARNED`, `GIFT_CARD_ISSUED`) and
recorded in `notification_emails` (idempotent per `requestId`), then sent after the commit:

- `jms`: JSON text message to queue `email` with `_awesome_ = com.awesome.testing.dto.email.EmailDto`,
  the message the backend sends (`EmailJmsProducerContractTest`, fixture shared with jms-email-consumer);
  the `consumer` delivers it to Mailpit.
- `local-outbox` (default): kept in memory (newest 500), read with `GET /api/v1/local/notifications/outbox`.

Outbox convention (ADR 3): `QUEUED` until the transport accepts it (`SENT`); failures back off 30 s,
doubling, capped at 30 minutes; after 10 attempts `FAILED` (counted in `notification_outbox_failed_total`).
Unknown templates or missing template properties are logged and the event is skipped. The backend's
random "training delay" before an email is not reproduced.

## Event feed consumer

`EventFeedPoller` polls `GET /internal/v1/events?after&limit=100&types=...` every
`NOTIFICATION_EVENTS_POLL_INTERVAL` (up to 20 pages per run) with `X-Service-Token`. One local
transaction per event locks the cursor row (`event_cursors`, consumer `notification-service`), skips
known ids (`processed_events`), applies the handler, records the id and advances the cursor. An
unavailable backend or a transient database error ends the run; any other failure is logged and the
event skipped. Needs backend 3.21.0 (older backends reject the new type names with 400).

## Correlation and tracing

`CorrelationFilter` keeps a valid `X-Request-Id` (or generates one) and puts `requestId`, `traceId` and
`spanId` into the MDC; feed events, webhook attempts and email sends run under the stored ids of the
backend request that caused them. Logs are plain lines with `[traceId,requestId]`, or ECS JSON with
`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`.

Tracing (contract section 4) is compiled in and off by default: Micrometer Tracing over the
OpenTelemetry bridge, OTLP HTTP exporter on the JDK HTTP client, W3C propagation only, no baggage. The
native image fixes bean conditions at build time, so `TRACING_ENABLED` switches a runtime sampler
(off: nothing sampled or exported). With tracing on, an incoming `traceparent` is the parent of the
server span, each feed event is handled in a `CONSUMER` span `process <type>` parented by the event's
`traceId`/`spanId`, webhook and email sends are `PRODUCER` spans parented by the stored `traceparent`,
calls to the backend carry the instrumented client's `traceparent` (instead of the hand-written child),
and the idle scheduled runs (feed poll, retry jobs, import) are not recorded. Micrometer's MDC listener
is silenced; the correlation code owns the MDC keys.

## Run

Requires Java 25 (Maven comes from the wrapper). PostgreSQL in every profile; H2 is test-scoped.

```bash
# Next to the backend's local profile (localhost:5432/testdb, schema "notification"), backend at :4001
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

| Profile | Database | Backend | Notes |
| --- | --- | --- | --- |
| (none) / `local` | `jdbc:postgresql://localhost:5432/testdb?currentSchema=notification` | `http://localhost:4001` | token `local-notification-token` |
| `docker` | `jdbc:postgresql://postgres:5432/testdb?currentSchema=notification` | `http://backend:4001` | `NOTIFICATION_SERVICE_TOKEN` required |

Compose runs `docker,demo` locally and `docker` on the server (no `demo`-specific settings exist).

### Environment variables

| Variable | Property | Default |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | datasource | see profiles |
| `BACKEND_BASE_URL` | `backend.base-url` | `http://localhost:4001` (`http://backend:4001` in `docker`) |
| `NOTIFICATION_SERVICE_TOKEN` | `notification.service-token` | `local-notification-token`; required in `docker`; `X-Service-Token` on every backend call |
| `NOTIFICATION_EVENTS_ENABLED` / `_POLL_INTERVAL` | `notification.events.*` | `true` / `PT5S` |
| `NOTIFICATION_EMAIL_TRANSPORT` | `notification.email.transport` | `local-outbox` (or `jms`) |
| `NOTIFICATION_EMAIL_RETRY_ENABLED` | `notification.email.retry.enabled` | `true` (every `PT30S`) |
| `SPRING_ARTEMIS_BROKER_URL` / `_USER` / `_PASSWORD` | `spring.artemis.*` | `tcp://activemq:61616`, `admin`/`admin` (used with `jms`) |
| `LOCAL_EMAIL_OUTBOX_ACCESS_KEY` | `notification.email.local-outbox.access-key` | empty (no key needed) |
| `WEBHOOKS_ALLOW_HTTP` | `webhooks.allow-http` | `true` |
| `WEBHOOKS_ALLOWED_PORTS` | `webhooks.allowed-ports` | empty |
| `WEBHOOKS_TIMEOUT` | `webhooks.timeout` | `PT5S` |
| `WEBHOOKS_MAX_ATTEMPTS` / `WEBHOOKS_INITIAL_BACKOFF` | `webhooks.max-attempts` / `.initial-backoff` | `8` / `PT1M` |
| `WEBHOOKS_AUTO_DISABLE_AFTER` | `webhooks.auto-disable-after` | `20` |
| `WEBHOOKS_MAX_SUBSCRIPTIONS_PER_USER` / `_MAX_INBOXES_PER_USER` / `_INBOX_REQUEST_LIMIT` | `webhooks.*` | `5` / `3` / `50` |
| `WEBHOOKS_ASYNC_DISPATCH` | `webhooks.async-dispatch` | `true` |
| `WEBHOOKS_RETRY_ENABLED` / `_INTERVAL` / `_INITIAL_DELAY` / `_BATCH_SIZE` | `webhooks.retry.*` | `true` / `PT30S` / `PT30S` / `20` |
| `WEBHOOKS_SECRET_ENCRYPTION_PASSWORD` / `_SALT` | `webhooks.secret-encryption.*` | development values; set both on servers (salt: hex, at least 8 bytes) |
| `WEBHOOKS_IMPORT_ENABLED` | `webhooks.import.enabled` | `true` |
| `SECURITY_RATE_LIMIT_ENABLED` | `security.rate-limit.enabled` | `false` |
| `SECURITY_RATE_LIMIT_WEBHOOK_TEST_USER_CAPACITY` / `_WINDOW` | `security.rate-limit.policies.webhook-test-user.*` | `10` / `15m` |
| `SECURITY_RATE_LIMIT_WEBHOOK_REDELIVER_USER_CAPACITY` / `_WINDOW` | `security.rate-limit.policies.webhook-redeliver-user.*` | `20` / `15m` |
| `TRACING_ENABLED` | `tracing.enabled` | `false` |
| `TRACING_OTLP_ENDPOINT` | `management.opentelemetry.tracing.export.otlp.endpoint` | `http://jaeger:4318/v1/traces` |
| `TRACING_SAMPLING_PROBABILITY` | `management.tracing.sampling.probability` | `1.0` (new traces, only when enabled) |
| `JWT_JWKS_REFRESH_INTERVAL` | `security.jwt.jwks-refresh-interval` | `10s` |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | actuator exposure | `health,prometheus` |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `logging.structured.format.console` | unset; `ecs` for JSON lines |

## Test

```bash
./mvnw verify                 # unit, contract and flow tests + PMD + JaCoCo (90% lines / 75% branches)
./mvnw -Pfast-verify verify   # same tests without PMD and JaCoCo
```

No Docker, Postgres or backend is needed. Flow tests boot the service on H2 (PostgreSQL mode, schema
`notification`) with `FakeBackend` (JWKS, an in-memory event feed read with the production decimal
handling, the webhook export), a `MutableClock`, stub DNS and a local HTTP receiver. The backend's
webhook tests are ported (`WebhookSubscriptionEndpointsTest`, `WebhookDeliveryFlowTest`,
`WebhookInboxFlowTest`, `WebhookUrlPolicyTest`, `WebhookHttpSenderTest` with WireMock,
`WebhookDispatcherTest`, `WebhookDeliveryLogTest`, `WebhookSignerTest`, `WebhookPayloadsTest`,
`CustomerEmailRendererTest`, `EmailJmsProducerContractTest` with an embedded Artemis broker).
`RestBackendClientContractTest` pins the internal-API requests with `MockRestServiceServer`;
`TracingFlowTest` checks the spans with an in-memory exporter.

## Native image

```bash
docker build -t notification-service:local .            # needs ~9 GB for the builder (Docker VM >= 12 GB)
scripts/native-smoke.sh notification-service:local      # Postgres, Artemis, busybox backend stub, 160 MiB limit
SMOKE_TRACING=true scripts/native-smoke.sh notification-service:local   # also checks the OTLP export
```

The build stage (`ghcr.io/graalvm/native-image-community:25`) runs `./mvnw -Pnative native:compile` with
the frozen `security/java/blocked.certs`; the runtime stage is `debian:trixie-slim` with the single
binary, UID 10001, `CMD ["-Xmx64m"]`. The Artemis client's native metadata is copied from the backend
(`NativeRuntimeHints`, `META-INF/native-image/com.awesome/notification-jms`). Published as
`slawekradzyminski/notification-service` by `publish-image.yml`; `Dockerfile.jvm` builds a JVM fallback.

## Release pipeline

Pushing a `vX.Y.Z` tag runs `.github/workflows/publish-image.yml`:

1. `validate` checks that the commit is on `master` and that the tag matches the project version.
2. `check-prior-verification` reads the git tree of the tagged commit and compares it with
   the trees of the commits behind the last 100 successful `ci.yml` runs (GitHub API,
   `actions: read`). PRs are squash-merged, so the tag commit is new but its tree is the
   tree of the PR head that passed CI (when the branch was up to date with `master`), or
   of the `master` push whose run has already finished. For `pull_request` runs GitHub
   tests the merge commit, which has that same tree when the branch is up to date.
3. When a matching run exists, the `verify` job (`./mvnw verify` plus the amd64 native
   build and smoke test) is skipped and the run summary links the CI run that verified
   the tree. Otherwise it runs as before. Manual (`workflow_dispatch`) prereleases always
   run it, and any API error falls back to the full verification. A failed or cancelled
   `verify` still blocks the release.
4. `build` compiles the native image on an amd64 and an arm64 runner, pushes each by
   digest and runs `scripts/native-smoke.sh` against that pushed image on both paths, so
   every published architecture is booted against Postgres; `publish` merges the digests.

A tag whose tree already passed `ci.yml` publishes in about 14 minutes instead of 20.

The release does not reuse the image from the PR: `ci.yml` builds `notification-service:native`
locally and never pushes it, and the published image carries the release commit in its
labels and is built on both architectures.

## Mutation testing

- PITest: `./mvnw -Pmutation-testing test-compile pitest:mutationCoverage` (scope: the webhook SSRF
  rules and URL policy, signer, payloads, delivery log and dispatcher, retry job, the email outbox and
  dispatcher, the renderer and the event feed poller). Advisory, not a gate.

# notification-service contributor guide

## Communication language

Use English for all communication related to this repository: responses, plans,
documentation, code comments, test names, commit messages and handoff notes.

## Contract

- The service boundary is section 2 (notification-service) and section 4 (tracing) of the
  distributed-lab contract (`awesome-localstack/docs/DISTRIBUTED_LAB_CONTRACTS.md`), plus ADR 1 and
  ADR 3 and `EVENT_FEED.md` in awesome-localstack. The backend never calls this service; there is no
  inbound internal API.
- The webhook API is backend 3.20.1's, unchanged: paths, JSON shapes, status codes, messages, limits,
  signature (`X-Webhook-Signature: t=..,v1=..`), SSRF rules, retry policy and rate limits. The ported
  backend tests pin it; change them only together with the contract.
- A webhook body built from a feed event must stay byte-for-byte the backend's body
  (`WebhookPayloadsTest`): read feed data with `FeedJson` (exact decimals), never with the
  application mapper.
- The JMS email message must stay the backend's (`EmailJmsProducerContractTest`, fixture
  `contracts/email-jms-contract.json` byte-identical to the backend and jms-email-consumer copies).
- Users and orders live in the backend; everything here is keyed by username.
- `RestBackendClientContractTest` pins the internal-API requests (JWKS, event feed, webhook export).
- Event feed handling: one local transaction per event (handle, record in `processed_events`,
  advance `event_cursors`); handlers implement `FeedEventHandler`, are idempotent, and register
  sends as after-commit work. Only poison events (malformed content, `EventFailures`) are skipped;
  database, transaction, backend-outage and unexpected failures leave the cursor for the next poll.
- Outboxes follow ADR 3 (emails) or keep the backend's documented webhook policy (deliveries).
- Correlation and tracing: `CorrelationFilter` and `Spans` own the MDC keys `requestId`/`traceId`/
  `spanId`; work outside a request (feed events, deliveries, emails) runs in a `Spans.start(...)`
  scope from the stored or received ids and closes it. Tracing is switched at runtime
  (`TracingConfig`), never with bean conditions.

# Verification Commands

- Fast local verify: `./mvnw -Pfast-verify verify`
- Default verify (CI default, PMD + JaCoCo 90/75 gate): `./mvnw verify`
- Targeted mutation feedback: `./mvnw -Pmutation-testing test-compile pitest:mutationCoverage`
- Native image (Docker with >= 12 GB memory): `docker build -t notification-service:local .`
  (JVM fallback: `docker build -f Dockerfile.jvm .`)
- Native smoke test: `scripts/native-smoke.sh notification-service:local`
  (`SMOKE_TRACING=true` for the tracing-on mode)

The default test run needs neither Docker nor a backend: flow tests use the in-memory
`FakeBackend`, a `MutableClock`, `StubWebhookHostResolver` and a local `StubReceiver`. The poller,
retry jobs and import are disabled in tests and called explicitly; sends run inline.

# Native-image rules

- The native image is PostgreSQL-only. H2 stays test-scoped.
- No runtime classpath scanning, no reflection without hints, no bean conditions
  (`@Profile`, `@ConditionalOn*`) that must change per deployment (Spring AOT fixes them at build
  time; use runtime properties such as `notification.email.transport` or `tracing.enabled`).
- Add Jackson binding hints in `NativeRuntimeHints` for every new DTO or type read from the backend.
- After changing dependencies, hints or configuration, rebuild the image and run the native smoke test.

# Mutation-testing workflow

- Run PITest after normal verification when you change the SSRF rules, URL policy, signer,
  payloads, delivery log, dispatchers, retry jobs, the email outbox, the renderer or the poller.
  Narrow it with `-DtargetClasses=... -DtargetTests=...` for small changes.
- Treat `NO_COVERAGE` as a reachability gap and `SURVIVED` as an assertion gap. Keep the run
  advisory; do not use the aggregate score as a gate.

# Change discipline

- Preserve unrelated working-tree changes.
- Keep README.md (run, env vars, profiles) in sync with `application*.yml`.
- Every controller endpoint needs `@Operation` (summary and description) and `@ApiResponse`
  entries; `OpenApiDocumentationTest` enforces it.

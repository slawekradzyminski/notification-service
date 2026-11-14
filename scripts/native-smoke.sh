#!/usr/bin/env bash
# Boots the notification-service image against a real Postgres (160 MiB container limit) and checks
# that the native binary migrates the schema, validates backend-style RS256 JWTs against a JWKS,
# imports the backend's webhooks (GET /internal/v1/webhooks/export), serves the webhook API (create,
# SSRF rejection, signed test ping into a built-in inbox), turns feed events into webhook deliveries
# (byte-exact body, signature with the imported secret, correlation ids) and commerce emails (local
# outbox and GET /api/v1/notifications/emails), and serves health, metrics and OpenAPI. Instead of a
# backend, a busybox nc stub publishes the JWKS, the export, the event feed (two events once the script
# touches /tmp/publish) and an OTLP HTTP sink. A second container of the same image then sends a
# commerce email with the jms transport to a real Artemis broker. Logs are ECS JSON, as on the server.
#   scripts/native-smoke.sh notification-service:native
#   SMOKE_TRACING=true scripts/native-smoke.sh notification-service:native   (tracing on: OTLP export checked)
set -euo pipefail

IMAGE="${1:?usage: native-smoke.sh IMAGE}"
BUSYBOX_IMAGE="busybox:1.37.0@sha256:a7dbc319b1438115a207e11930dc4bb3e643dcb3ec0bdd694bb36cd8fad289ec"
POSTGRES_IMAGE="postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
ARTEMIS_IMAGE="apache/artemis:2.55.0@sha256:a192eee9c46e6352625ca9dbf0beebe4a34c363111c628bbb10c4aff1227341d"
RUN_ID="notification-smoke-$$"
NETWORK="$RUN_ID"
PORT="${SMOKE_PORT:-14005}"
JMS_PORT="${SMOKE_JMS_PORT:-14006}"
TRACING="${SMOKE_TRACING:-false}"
SERVICE_TOKEN="native-smoke-service-token"
TRACE_ID="4bf92f3577b34da6a3ce929d0e0e4736"
BACKEND_SPAN="00f067aa0ba902b7"
IMPORTED_SECRET="whsec_smoke-imported-secret-0123456789"
INBOX_TOKEN="smoke-inbox-token-0001"
ORDER_EVENT="00000000-0000-0000-0000-0000000000e1"

cleanup() {
  status=$?
  if [[ $status -ne 0 ]]; then
    echo "--- notification-service log ---" >&2
    docker logs "$RUN_ID-app" >&2 2>&1 | tail -n 80 || true
    echo "--- notification-service (jms) log ---" >&2
    docker logs "$RUN_ID-jms" >&2 2>&1 | tail -n 40 || true
    echo "--- backend stub requests ---" >&2
    docker exec "$RUN_ID-backend" cat /tmp/requests.log >&2 2>&1 || true
  fi
  docker rm -f "$RUN_ID-app" "$RUN_ID-jms" "$RUN_ID-db" "$RUN_ID-backend" "$RUN_ID-artemis" >/dev/null 2>&1 || true
  rm -rf "$WORK"
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  exit $status
}
WORK=$(mktemp -d)
trap cleanup EXIT

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
hex_b64url() { tr -d '\n' | perl -e 'local $/; print pack("H*", <STDIN>)' | b64url; }

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/key.pem" 2>/dev/null
MODULUS=$(openssl rsa -in "$WORK/key.pem" -noout -modulus | cut -d= -f2 | hex_b64url)
EXPONENT=$(printf '010001' | hex_b64url)
KID="native-smoke-key"
mkdir -p "$WORK/srv"
printf '{"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"%s","n":"%s","e":"%s"}]}' \
  "$KID" "$MODULUS" "$EXPONENT" > "$WORK/srv/jwks.json"
printf '{"subscriptions":[{"id":41,"username":"smoke-client","url":"inbox://%s","events":["order.created"],"secret":"%s","active":true,"description":"imported","createdAt":"2026-11-01T10:00:00Z","updatedAt":"2026-11-01T10:00:00Z","consecutiveFailures":0}],"inboxes":[{"token":"%s","username":"smoke-client","createdAt":"2026-11-01T10:00:00Z"}]}' \
  "$INBOX_TOKEN" "$IMPORTED_SECRET" "$INBOX_TOKEN" > "$WORK/srv/export.json"
printf '{"events":[{"sequence":1,"id":"%s","type":"order.created","occurredAt":"2026-11-10T12:00:00.120Z","orderId":101,"username":"smoke-client","data":{"orderId":101,"status":"PENDING","totalAmount":40.00},"requestId":"smoke-order-1","traceId":"%s","spanId":"%s"},{"sequence":2,"id":"00000000-0000-0000-0000-0000000000e2","type":"notification.requested","occurredAt":"2026-11-10T12:00:01Z","orderId":101,"username":"smoke-client","data":{"properties":{"amount":"40.00","orderId":"101"},"recipientEmail":"smoke.client@example.com","recipientName":"Smoke Client","requestId":"00000000-0000-0000-0000-00000000a002","template":"PAYMENT_RECEIVED"},"requestId":"smoke-pay-1","traceId":"%s","spanId":"%s"}],"nextCursor":2,"hasMore":false}' \
  "$ORDER_EVENT" "$TRACE_ID" "$BACKEND_SPAN" "$TRACE_ID" "$BACKEND_SPAN" > "$WORK/srv/events.json"

# One HTTP exchange per connection (nc -lk -e): JWKS, webhook export, event feed and an OTLP HTTP sink.
cat > "$WORK/srv/backend.sh" <<'STUB'
#!/bin/sh
read -r method path _
length=0
token=""
request_id=""
traceparent=""
while IFS= read -r line; do
  line=$(printf '%s' "$line" | tr -d '\r')
  [ -z "$line" ] && break
  case "$line" in
    [Cc]ontent-[Ll]ength:*) length=${line#*: } ;;
    [Xx]-[Ss]ervice-[Tt]oken:*) token=${line#*: } ;;
    [Xx]-[Rr]equest-[Ii]d:*) request_id=${line#*: } ;;
    [Tt]raceparent:*) traceparent=${line#*: } ;;
  esac
done
status="200 OK"
type="application/json"
case "$method $path" in
  "POST /v1/traces")
    [ "$length" -gt 0 ] && head -c "$length" >> /tmp/traces
    type="application/x-protobuf"; response="" ;;
  "GET /internal/v1/jwks.json") response=$(cat /srv/jwks.json) ;;
  "GET /internal/v1/webhooks/export")
    printf 'EXPORT %s\n' "$token" >> /tmp/requests.log
    response=$(cat /srv/export.json) ;;
  "GET /internal/v1/events?"*)
    printf 'FEED %s|%s|%s|%s\n' "$path" "$token" "$request_id" "$traceparent" >> /tmp/requests.log
    after=$(printf '%s' "$path" | sed -n 's/.*[?&]after=\([0-9]*\).*/\1/p')
    if [ "${after:-0}" = 0 ] && [ -f /tmp/publish ]; then
      response=$(cat /srv/events.json)
    else
      response=$(printf '{"events":[],"nextCursor":%s,"hasMore":false}' "${after:-0}")
    fi ;;
  *) status="404 Not Found"; response='{"message":"Not found"}' ;;
esac
printf '%s %s\n' "$method" "$path" >> /tmp/requests.log
printf 'HTTP/1.1 %s\r\nContent-Type: %s\r\nContent-Length: %s\r\nConnection: close\r\n\r\n%s' \
  "$status" "$type" "${#response}" "$response"
STUB
chmod -R a+rX "$WORK/srv"
chmod a+x "$WORK/srv/backend.sh"

token() {
  local now header payload signature
  now=$(date +%s)
  header=$(printf '{"alg":"RS256","kid":"%s"}' "$KID" | b64url)
  payload=$(printf '{"sub":"%s","auth":[{"authority":"%s"}],"iat":%d,"exp":%d}' "$1" "$2" "$now" $((now + 600)) | b64url)
  signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$WORK/key.pem" -binary | b64url)
  printf '%s.%s.%s' "$header" "$payload" "$signature"
}
status_of() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
expect() {
  if [[ "$2" != "$3" ]]; then
    echo "FAIL $1: expected $3, got $2" >&2
    exit 1
  fi
  echo "PASS $1"
}
contains() {
  if ! grep -q -- "$3" <<< "$2"; then
    echo "FAIL $1: $3 not in $2" >&2
    exit 1
  fi
  echo "PASS $1"
}
wait_for() { # url
  for _ in $(seq 1 90); do
    [[ "$(status_of "$1/actuator/health")" == 200 ]] && return 0
    sleep 1
  done
  return 1
}

docker network create "$NETWORK" >/dev/null
docker run -d --name "$RUN_ID-backend" --network "$NETWORK" -v "$WORK/srv:/srv:ro" \
  "$BUSYBOX_IMAGE" nc -lk -p 4001 -e /srv/backend.sh >/dev/null
docker run -d --name "$RUN_ID-db" --network "$NETWORK" \
  -e POSTGRES_DB=notification -e POSTGRES_USER=notification -e POSTGRES_PASSWORD=notification \
  "$POSTGRES_IMAGE" >/dev/null
docker run -d --name "$RUN_ID-artemis" --network "$NETWORK" -e ARTEMIS_USER=admin -e ARTEMIS_PASSWORD=admin \
  -e ANONYMOUS_LOGIN=true -e EXTRA_ARGS="--no-autotune --global-max-size 64M" "$ARTEMIS_IMAGE" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$RUN_ID-db" pg_isready -U notification -d notification >/dev/null 2>&1 && break
  sleep 1
done
sleep 2
docker exec "$RUN_ID-db" psql -q -U notification -d notification -c 'CREATE DATABASE notification_jms' >/dev/null

run_app() { # name port database transport
  docker run -d --name "$RUN_ID-$1" --network "$NETWORK" --memory 160m -p "$2:4005" \
    -e SPRING_PROFILES_ACTIVE=docker \
    -e SPRING_DATASOURCE_URL="jdbc:postgresql://$RUN_ID-db:5432/$3?currentSchema=notification" \
    -e SPRING_DATASOURCE_USERNAME=notification -e SPRING_DATASOURCE_PASSWORD=notification \
    -e NOTIFICATION_SERVICE_TOKEN="$SERVICE_TOKEN" -e NOTIFICATION_EMAIL_TRANSPORT="$4" \
    -e SPRING_ARTEMIS_BROKER_URL="tcp://$RUN_ID-artemis:61616" \
    -e NOTIFICATION_EVENTS_POLL_INTERVAL=PT1S -e BACKEND_BASE_URL="http://$RUN_ID-backend:4001" \
    -e WEBHOOKS_SECRET_ENCRYPTION_PASSWORD=native-smoke-encryption-password \
    -e WEBHOOKS_SECRET_ENCRYPTION_SALT=a1b2c3d4e5f60718293a4b5c6d7e8f90 \
    -e TRACING_ENABLED="$TRACING" -e TRACING_OTLP_ENDPOINT="http://$RUN_ID-backend:4001/v1/traces" \
    -e LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs \
    "$IMAGE" >/dev/null
}

started=$(date +%s)
run_app app "$PORT" notification local-outbox
base="http://localhost:$PORT"
wait_for "$base" || true
expect "health" "$(status_of "$base/actuator/health")" 200
echo "ready after $(( $(date +%s) - started ))s"

metrics=$(curl -sf "$base/actuator/prometheus")
contains "outbox pending gauge" "$metrics" '^notification_outbox_pending '
docs=$(curl -sf "$base/v3/api-docs")
contains "OpenAPI lists webhook paths" "$docs" '"/api/v1/webhooks/{id}/deliveries/{deliveryId}/redeliver"'
contains "OpenAPI lists the email list" "$docs" '"/api/v1/notifications/emails"'
contains "OpenAPI lists the local outbox" "$docs" '"/api/v1/local/notifications/outbox"'
if grep -q '/internal/' <<< "$docs"; then echo "FAIL OpenAPI exposes the internal API" >&2; exit 1; fi

CLIENT=$(token smoke-client ROLE_CLIENT)
ADMIN=$(token smoke-admin ROLE_ADMIN)
auth=(-H "Authorization: Bearer $CLIENT")

# One-time import of the backend's webhooks (ids, inbox tokens and secrets kept).
imported=""
for _ in $(seq 1 20); do
  imported=$(curl -sf "${auth[@]}" "$base/api/v1/webhooks" || true)
  grep -q '"id":41' <<< "$imported" && break
  sleep 0.5
done
contains "imported subscription keeps its id" "$imported" '"id":41,"username":"smoke-client","url":"inbox://smoke-inbox-token-0001"'
contains "export called with the service token" "$(docker exec "$RUN_ID-backend" cat /tmp/requests.log)" "EXPORT $SERVICE_TOKEN"

expect "missing token" "$(status_of "$base/api/v1/webhooks")" 401
expect "client role forbidden on admin list" "$(status_of "${auth[@]}" "$base/api/v1/admin/webhooks")" 403
expect "admin list" "$(status_of -H "Authorization: Bearer $ADMIN" "$base/api/v1/admin/webhooks")" 200
ssrf=$(curl -s -w '\n%{http_code}' -X POST "${auth[@]}" -H 'Content-Type: application/json' \
  -d '{"url":"http://169.254.169.254/latest/meta-data/","events":["order.created"]}' "$base/api/v1/webhooks")
expect "SSRF target rejected" "$(tail -n1 <<< "$ssrf")" 400
contains "SSRF reason" "$ssrf" 'resolves to a link-local address'
created=$(curl -s -w '\n%{http_code}' -X POST "${auth[@]}" -H 'Content-Type: application/json' \
  -d "{\"url\":\"inbox://$INBOX_TOKEN\",\"events\":[\"webhook.ping\"]}" "$base/api/v1/webhooks")
expect "subscription created" "$(tail -n1 <<< "$created")" 201
contains "new ids continue after the imported ones" "$created" '"id":42'
ping=$(curl -s -w '\n%{http_code}' -X POST "${auth[@]}" "$base/api/v1/webhooks/42/test")
expect "test ping" "$(tail -n1 <<< "$ping")" 201
contains "ping delivered to the inbox" "$ping" '"status":"DELIVERED"'

# Feed events: a webhook delivery (byte-compatible body) and a commerce email.
docker exec "$RUN_ID-backend" touch /tmp/publish
requests=""
for _ in $(seq 1 30); do
  requests=$(curl -sf "${auth[@]}" "$base/api/v1/webhook-inboxes/$INBOX_TOKEN/requests" || true)
  grep -q 'order.created' <<< "$requests" && break
  sleep 0.5
done
python3 - "$requests" "$IMPORTED_SECRET" "$ORDER_EVENT" "$TRACE_ID" "$BACKEND_SPAN" <<'PY'
import hashlib, hmac, json, re, sys
requests, secret, event, trace, span = sys.argv[1:]
delivered = [r for r in json.loads(requests) if r["headers"].get("X-Webhook-Event") == "order.created"]
assert len(delivered) == 1, f"FAIL one order.created delivery expected: {requests}"
request = delivered[0]
expected = ('{"id":"%s","type":"order.created","createdAt":"2026-11-10T12:00:00.120Z",'
            '"data":{"orderId":101,"status":"PENDING","totalAmount":40.00}}' % event)
assert request["body"] == expected, f"FAIL webhook body: {request['body']}"
print("PASS webhook body is byte-compatible with the backend's")
t, v1 = re.fullmatch(r"t=(\d+),v1=([0-9a-f]{64})", request["headers"]["X-Webhook-Signature"]).groups()
digest = hmac.new(secret.encode(), f"{t}.{request['body']}".encode(), hashlib.sha256).hexdigest()
assert hmac.compare_digest(digest, v1), "FAIL signature with the imported secret"
print("PASS signed with the imported secret")
assert request["headers"]["X-Request-Id"] == "smoke-order-1", request["headers"]
assert re.fullmatch(f"00-{trace}-[0-9a-f]{{16}}-01", request["headers"]["traceparent"]), request["headers"]
assert span not in request["headers"]["traceparent"]
print("PASS delivery carries the backend request id and a child of its trace")
PY

outbox=""
for _ in $(seq 1 20); do
  outbox=$(curl -sf -H "Authorization: Bearer $ADMIN" "$base/api/v1/local/notifications/outbox" || true)
  grep -q 'PAYMENT_RECEIVED' <<< "$outbox" && break
  sleep 0.5
done
contains "email rendered into the local outbox" "$outbox" '"subject":"Payment received for order #101"'
contains "email body" "$outbox" 'We received your payment of 40.00 USD for order #101.'
emails=$(curl -sf "${auth[@]}" "$base/api/v1/notifications/emails")
contains "email listed as sent and masked" "$emails" '"status":"SENT","recipientMasked":"s\*\*\*@example.com"'
expect "outbox is for administrators" "$(status_of "${auth[@]}" "$base/api/v1/local/notifications/outbox")" 403
feed=""
for _ in $(seq 1 20); do
  feed=$(docker exec "$RUN_ID-backend" grep '^FEED .*after=2&' /tmp/requests.log 2>/dev/null | tail -n1 || true)
  [[ -n "$feed" ]] && break
  sleep 0.5
done
contains "feed cursor advanced" "$feed" 'after=2&limit=100&'
contains "feed asks for webhook types and notification.requested" "$feed" \
  'types=notification.requested,order.cancelled,order.created,order.paid,order.reservation-expired,payment.failed,refund.booked,shipment.delivered,shipment.shipped|'
contains "feed request carries the service token" "$feed" "|$SERVICE_TOKEN|"

app_log=$(docker logs "$RUN_ID-app" 2>&1)
email_line=$(grep -m1 '"message":"Queued PAYMENT_RECEIVED email' <<< "$app_log" || true)
contains "JSON log line has the event's request id" "$email_line" '"requestId":"smoke-pay-1"'
contains "JSON log line has the event's trace id" "$email_line" "\"traceId\":\"$TRACE_ID\""
if grep -v '^{' <<< "$app_log" | grep -q .; then
  echo "FAIL non-JSON log lines with LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs:" >&2
  grep -v '^{' <<< "$app_log" | head -n5 >&2
  exit 1
fi
echo "PASS every log line is JSON"
pending=$(curl -sf "$base/actuator/prometheus" | grep '^notification_outbox_pending ')
expect "nothing left pending" "$pending" "notification_outbox_pending 0.0"

if [[ "$TRACING" == true ]]; then
  expect "traced request" "$(status_of -H "traceparent: 00-$TRACE_ID-$BACKEND_SPAN-01" "$base/actuator/health")" 200
  for _ in $(seq 1 30); do
    exported=$(docker exec "$RUN_ID-backend" sh -c 'cat /tmp/traces 2>/dev/null || true' | tr -c '[:print:]' ' ')
    grep -q 'process order.created' <<< "$exported" && grep -q 'webhook order.created' <<< "$exported" && break
    sleep 1
  done
  grep -q 'notification-service' <<< "$exported" && grep -q 'process order.created' <<< "$exported" \
    && grep -q 'webhook order.created' <<< "$exported" && grep -q 'email PAYMENT_RECEIVED' <<< "$exported" \
    && echo "PASS OTLP HTTP export of the consumer, delivery and email spans (service notification-service)" \
    || { echo "FAIL no OTLP export with the expected spans: $exported" >&2; exit 1; }
else
  sleep 6
  [[ -z "$(docker exec "$RUN_ID-backend" sh -c 'cat /tmp/traces 2>/dev/null')" ]] \
    && echo "PASS tracing off: nothing exported" \
    || { echo "FAIL tracing off exported spans" >&2; exit 1; }
fi
docker stats --no-stream --format 'memory (local outbox): {{.MemUsage}}' "$RUN_ID-app"

# The jms transport: the same image sends the commerce email to a real Artemis broker.
run_app jms "$JMS_PORT" notification_jms jms
jms_base="http://localhost:$JMS_PORT"
wait_for "$jms_base" || true
expect "health (jms)" "$(status_of "$jms_base/actuator/health")" 200
jms_emails=""
for _ in $(seq 1 40); do
  jms_emails=$(curl -sf "${auth[@]}" "$jms_base/api/v1/notifications/emails" || true)
  grep -q '"status":"SENT"' <<< "$jms_emails" && break
  sleep 0.5
done
contains "email sent through Artemis" "$jms_emails" '"template":"PAYMENT_RECEIVED","subject":"Payment received for order #101","status":"SENT"'
queue=$(docker exec "$RUN_ID-artemis" /var/lib/artemis-instance/bin/artemis queue stat --user admin --password admin \
  --queueName email --url tcp://localhost:61616 2>/dev/null | grep '|email' || true)
contains "message waiting on queue email" "$queue" '|email *|.*| *1 *|'
expect "local outbox gone with jms" "$(status_of -H "Authorization: Bearer $ADMIN" "$jms_base/api/v1/local/notifications/outbox")" 404
docker stats --no-stream --format 'memory (jms): {{.MemUsage}}' "$RUN_ID-jms"
echo "native smoke test passed"

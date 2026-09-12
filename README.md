# wasparks-api-service

The public developer API for WaSparks — Java 21, Spring Boot 3.3, port **8083**, public host
**developer.wasparks.com** (docs at **developers.wasparks.com**).

It is a **gateway, not a second send path**. Every WhatsApp action is performed by tenants-service over
its docker-network-only `/internal/v1/**` surface. What lives here is everything tenants-service must
not have to know about: API keys, plans, rate limits, message quotas, idempotency, the send queue,
usage rollups and outbound webhooks. It is also the only service in the estate that talks to Redis, and
it never calls Meta directly.

---

## Running it locally

You need **PostgreSQL** (the shared `whatsapp_admin` database the other services use) and **Redis**.
Postgres you already have; Redis you do not, and it is the one thing you will need Docker for:

```bash
docker compose -f docker-compose.dev.yml up -d
```

That starts `redis:7-alpine` on `localhost:6379` with append-only persistence. If something else on the
machine already holds 6379 (`Bind for 0.0.0.0:6379 failed: port is already allocated`), give this one
its own port rather than sharing a keyspace with another project:

```bash
REDIS_HOST_PORT=6380 docker compose -f docker-compose.dev.yml up -d
```

and set `REDIS_URL=redis://localhost:6380` in the launch configuration. Then run
`ApiServiceApplication` from Eclipse, or:

```bash
mvn spring-boot:run
```

The `dev` profile is active by default and every secret has a development fallback, so a local run
needs no environment at all. Check it came up:

```bash
curl http://localhost:8083/actuator/health
```

Swagger UI is at <http://localhost:8083/docs>, and the hand-written quickstart is in
[`docs/quickstart.md`](docs/quickstart.md).

### Schema

No Flyway (shared-contracts §4). Apply `modules/_shared/database/020_api_ecosystem.sql` and then
`020a_api_keys_ui_session.sql` by hand, once per environment, before first boot — `ddl-auto=validate` will refuse to start otherwise, which is the
point.

### Tests

```bash
mvn test
```

120 tests. They run against a **real PostgreSQL and a real Redis** through Testcontainers, so Docker
must be running. The schema comes from `src/test/resources/db/schema-test.sql`, a trimmed transcript of
the real migrations, and the entities are validated against it exactly as they are in production.

> **Docker Engine 29+.** The docker-java client bundled with Testcontainers asks for an API version the
> engine no longer accepts, and the failure reads as the unhelpful "Could not find a valid Docker
> environment". The build pins it (`docker.api.version`, default 1.44). On an engine older than 25.0,
> override with `-Ddocker.api.version=1.41`.

---

## Configuration

| Variable | Required | Default (dev) | What it is |
|---|---|---|---|
| `DB_URL` | prod | `jdbc:postgresql://localhost:5432/whatsapp_admin` | The shared database. Same instance as admin-service and tenants-service. |
| `DB_USERNAME` | prod | `postgres` | |
| `DB_PASSWORD` | prod | `admin123` | The shared dev password the other two services default to. |
| `REDIS_URL` | prod | `redis://localhost:6379` | The only Redis in the estate. In compose: `redis://redis:6379`. |
| `ENCRYPTION_SECRET` | **prod, must match** | dev default | AES-256-GCM key for webhook secrets. **Identical to admin-service and tenants-service** or values written by one cannot be read by another (shared-contracts §1). |
| `TENANTS_JWT_SECRET` | **prod, must match** | dev default | HS256 key used to *verify* tenant sessions on `/v1/keys`. Identical to tenants-service. 32+ chars. |
| `INTERNAL_API_SECRET` | **prod, must match** | dev default | Shared bearer for `/internal/v1/**`. Identical to tenants-service. 32+ chars. |
| `TENANTS_SERVICE_BASE_URL` | prod | `http://localhost:8081` | In compose: `http://tenants-service:8081`. |
| `API_PUBLIC_BASE_URL` | no | `http://localhost:8083` | Shown in the docs and the quickstart. |
| `APP_CORS_ALLOWED_ORIGINS` | no | `http://localhost:5173` | Comma-separated browser origins allowed on `/v1/**` (tenant-web). Prod: `https://app.wasparks.com`. `/meta/**` stays CORS-disabled — nothing there is ever called from a browser. |
| `SEND_WORKERS` | no | `8` | Send-worker threads. **`0` accepts sends but drains nothing** — a valid shape if you separate API and worker instances. |
| `API_PREFLIGHT_ENABLED` | no | `true` | Upstream preflight. See below. |
| `INTERNAL_TIMEOUT_MS` | no | `10000` | Timeout on every internal call. |
| `API_KEY_CACHE_TTL_SECONDS` | no | `300` | How long a resolved key is cached. Bounds only out-of-band changes; a revoke is immediate. |
| `SEND_STREAM` / `SEND_STREAM_MAXLEN` | no | `sends` / `100000` | The Redis Stream and its approximate cap. |
| `SEND_RETRY_BACKOFF_MS` | no | `2000,10000,30000` | Worker retries on upstream 5xx. |
| `WEBHOOK_RETRY_BACKOFF_MS` | no | `10000,60000,300000,1800000,7200000` | Delivery retry schedule, then EXHAUSTED. |
| `WEBHOOK_PAUSE_AFTER_FAILURES` | no | `100` | Consecutive failures before an endpoint is PAUSED. |
| `USAGE_FLUSH_MS` | no | `600000` | Redis counters → `api_usage_daily`. |

**The prod profile refuses to boot** if `ENCRYPTION_SECRET`, `TENANTS_JWT_SECRET` or
`INTERNAL_API_SECRET` is missing, too short, or still a development default. That guard exists because
this estate has already shipped a production release signing every tenant session with a secret
printed in the repository, and nothing failed — which is exactly why nobody noticed.

### `API_PREFLIGHT_ENABLED`

A send is checked twice: locally (recipient shape, supported type, number ownership) and then once
synchronously against `POST /internal/v1/messages/validate`, which covers suppression, the 24-hour
window, template approval, account state and the tier cap. Only when both pass is the message queued,
so a client never gets a `202` for something we could have refused outright.

Setting this to `false` skips only the upstream half. The local checks still run and the worker still
validates for real before sending, so the cost is that some rejections arrive as a `message.failed`
webhook instead of a synchronous `4xx`. It exists for developing against a tenants-service that has
not shipped `/validate`; leave it on everywhere else.

---

## How a request flows

```
X-API-Key ──▶ auth ──▶ rate limit ──▶ scope ──▶ idempotency ──▶ quota ──▶ handler
                                                (send only)     (send only)
```

The order is load-bearing. A request that is over its rate limit must not reserve an idempotency key; a
request whose key lacks the scope must not consume the tenant's message quota; a replayed request must
consume neither.

A send that passes all of it is written to the `sends` Redis Stream and answered `202`. `SendWorker`
drains the stream, calls tenants-service, and the outcome reaches the client by webhook or from
`GET /v1/messages/{id}`.

Meanwhile `OutboxPoller` drains `api_outbox_events` — rows tenants-service writes in the same
transaction as the state change — fans each event out to the tenant's matching endpoints, and
`WebhookDispatcher` delivers them with an HMAC signature and a retry schedule.

## Endpoints

| Method | Path | Auth | Scope |
|---|---|---|---|
| POST | `/meta/whatsapp/{version}/{phone_number_id}/messages` | API key | `messages:send` |
| GET | `/v1/messages/{id}` | API key | `messages:read` |
| GET | `/v1/templates` · `/v1/templates/{id}` | API key | `templates:read` |
| POST | `/v1/templates` · PATCH `/{id}` · POST `/{id}/submit` · `/{id}/refresh` · DELETE `/{id}` | API key | `templates:write` |
| GET | `/v1/account` | API key | `account:read` |
| GET/POST | `/v1/webhooks` · GET/PATCH/DELETE `/{id}` · POST `/{id}/test` · GET `/{id}/deliveries` · POST `/{id}/deliveries/{deliveryId}/retry` | API key | `webhooks:manage` |
| GET/POST | `/v1/keys` · DELETE `/v1/keys/{id}` | **tenant JWT** (OWNER/ADMIN) | — |
| GET | `/actuator/health` · `/docs` · `/v3/api-docs` | public | — |

## Deploying

Image `wasparks/api-microservice`. Compose needs `redis` (`redis:7-alpine`, `--appendonly yes`, volume,
**no host port**) and this service depending on postgres, redis and tenants-service. nginx maps
`developer.wasparks.com` here and `developers.wasparks.com` to `/docs`, and — belt and braces —
`api.wasparks.com` must keep `location /internal/ { return 404; }`.

## Known gaps

- **Webhook auto-pause does not email the tenant** (epic §B8). An endpoint paused after 100 consecutive
  failures is logged and visible in tenant-web, but nobody is told. P1.1; it needs the admin-service
  `EmailService` contract and a `noreply` template.
- **`api_usage_daily.messages_sent` / `messages_failed` stay zero.** This service sees an accept, not a
  delivery. The nightly reconciliation from `messages` that fills them is P1.1.
- **Partner (white-label) support is schema only.** `api_partners` is mapped and `partner_id` travels
  through the principal, but nothing writes it in P1.

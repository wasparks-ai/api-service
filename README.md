# wasparks-api-service

The public developer API for WaSparks — Java 21, Spring Boot 3.3, port **8083**, public host
**developer.wasparks.com** (docs at **developers.wasparks.com**).

It is a **gateway, not a second send path**. Every WhatsApp action is performed by tenants-service over
its docker-network-only `/internal/v1/**` surface, and every tenant is created by admin-service over its
own. What lives here is everything neither of them should have to know about: API keys, plans, rate
limits, message quotas, idempotency, the send queue, usage rollups and outbound webhooks. It is also the
only service in the estate that talks to Redis, and it never calls Meta directly.

It is also the **partner platform** (api-partner epic): a partner is a tenant that resells WhatsApp to
its own users, registers them as customers here, connects their numbers through hosted setup links, and
sends and receives on their behalf with one key and one webhook endpoint. See
[`docs/partners.md`](docs/partners.md).

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

No Flyway (shared-contracts §4). Apply `modules/_shared/database/020_api_ecosystem.sql`, then
`020a_api_keys_ui_session.sql`, then `021_api_partners.sql` by hand, once per environment, before first
boot — `ddl-auto=validate` will refuse to start otherwise, which is the point.

### Tests

```bash
mvn test
```

234 tests. They run against a **real PostgreSQL and a real Redis** through Testcontainers, so Docker
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
| `ADMIN_SERVICE_BASE_URL` | **prod** | `http://localhost:8080` | The second internal upstream: it creates a partner's client tenants and answers the "is this tenant a partner?" resolve. In compose: `http://admin-service:8080`. admin-service needs the same `INTERNAL_API_SECRET`, and nginx must `return 404` for `/internal/` on `admin-api.wasparks.com`. |
| `APP_PUBLIC_BASE_URL` | **prod** | `http://localhost:5173` | Where a hosted setup link points — tenant-web serves `/setup/{token}`. Prod: `https://app.wasparks.com`. A wrong value here produces links that 404 for a partner's customer. |
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
| `API_PARTNER_CACHE_TTL_SECONDS` | no | `60` | How long the partner → clients hash is cached. Bounds only out-of-band changes; a create, suspend or cap change invalidates it immediately. |
| `API_MAX_INLINE_RECIPIENTS` | no | `5000` | Recipients or members accepted in one campaign/audience request. |
| `API_MAX_CSV_SIZE` | no | `10MB` | Recipient CSV cap. tenants-service applies its own 16 MB limit too; the smaller of the two wins. |
| `API_SETUP_LINK_TTL_HOURS` | no | `168` | Default setup-link lifetime when a partner does not ask for one. |
| `API_SCHEDULED_QUOTA_POLL_MS` / `API_SCHEDULED_QUOTA_LOOKAHEAD_MINUTES` | no | `60000` / `5` | How often, and how far ahead, scheduled campaigns have their quota reserved. |

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
transaction as the state change — fans each event out to the tenant's matching endpoints **and to the
endpoints of the partner that owns that tenant, if any**, and `WebhookDispatcher` delivers them with an
HMAC signature and a retry schedule.

On a partner key the pipeline gains one step before all of it: `X-Tenant-Id` is resolved to a *verified*
acting tenant in the auth filter, so every later stage — the limiters, the quota counters, the internal
client — reads one field and cannot be pointed at a customer the key does not hold. That resolution is
the security boundary of the whole partner surface; see `PartnerTenantResolver`.

A **client key** — one a partner issued for a single customer — goes through the same step. It carries no
`partner_id` and sends no `X-Tenant-Id`, but its tenant is somebody's customer, so it runs in that
partner's context: the pooled allowance, the cap the partner set, and the same `403 customer_suspended`.
The exception is a client tenant an admin has explicitly assigned a plan, which keeps that plan and its
own counters.

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
| POST/GET | `/v1/customers` · GET/PATCH `/{id}` | **partner key** | `customers:read` / `customers:write` |
| POST/GET | `/v1/customers/{id}/setup-links` · GET `/{linkId}` · POST `/{linkId}/cancel` | partner key | `customers:*` |
| POST/GET | `/v1/customers/{id}/phone-numbers` (direct mapping) | partner key | `customers:*` |
| POST/GET | `/v1/customers/{id}/keys` (client keys) | partner key | `customers:*` |
| POST/GET | `/v1/campaigns` · GET `/{id}` · POST `/{id}/start`, `/pause`, `/resume`, `/cancel` · GET/POST `/{id}/recipients` | API key | `campaigns:read` / `campaigns:write` |
| POST/GET | `/v1/audiences` · GET `/{id}` · GET/POST/DELETE `/{id}/members` · DELETE `/{id}` | API key | `campaigns:*` |
| POST | `/v1/uploads/csv` (multipart, streamed) | API key | `campaigns:write` |
| GET | `/v1/media/{messageId}` → 302 | API key | `media:read` |
| GET | `/v1/webhooks/events` | API key | `webhooks:manage` |
| various | `/v1/partner/**` — the console: customers, setup links, keys, webhooks, usage, campaigns (incl. the merged cross-client list and `/campaigns/{id}/recipients`) | **tenant JWT** (OWNER/ADMIN of a partner) | — |
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
- **`PATCH /internal/v1/tenants/{id}/settings` does not exist yet.** The Partner console's
  frequency-guard switch (`PATCH /v1/partner/customers/{id}/settings`) is built against it and starts
  working the moment tenants-service ships it; until then it answers `404`. It is the one internal
  endpoint this build needed that `internal.md` lacks.
- **`app_access` is stored and reported but nothing acts on it** — the branded client login is a later
  phase of the partner epic.
- **`GET /v1/templates` still returns tenants-service's paged shape**, not the `{data, meta}`
  envelope every other collection read answers in. It shipped that way in P1 and is live, so
  normalising it is a breaking change somebody has to decide on rather than a tidy-up;
  `ListEnvelopeContractTest` names it as the one exemption so the gap is visible instead of absent.
- **Two internal endpoints this build calls are not in `internal.md`**: `GET
  /internal/v1/tenants/{id}/settings` (only the PATCH is documented) and `GET
  /internal/v1/campaigns/across`. Both are implemented against the paths the hand-off named and read
  defensively, and `minDaysBetweenMarketing` degrades to `0` if the GET is missing — but the shapes are
  assumptions until `internal.md` catches up.
- **`GET /v1/partner/campaigns/{id}/recipients` without `customerId` costs a lookup per customer.** There
  is no cross-tenant campaign read, so the owner is found by asking each of the partner's tenants in
  turn. The console passes `customerId` from the row it was already showing; the fallback is for a pasted
  id. A partner with hundreds of customers should not rely on it.

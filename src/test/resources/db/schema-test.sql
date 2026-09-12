-- Test schema for wasparks-api-service.
--
-- The integration tests run against ddl-auto=validate, exactly as dev and prod do, so this file has to
-- produce the real shapes rather than letting Hibernate generate its own. It is a trimmed transcript of
-- the shared migrations: enough of 001 for the two tables this service only READS (tenants,
-- whatsapp_accounts, plus the FK targets admins and tenant_users), then 020_api_ecosystem.sql in full
-- for the tables it owns.
--
-- Two things are load-bearing and would be lost if the tests used generated DDL:
--   * tenants.status and whatsapp_accounts.status are real PostgreSQL ENUM types, and the read-only
--     projections map them with PostgreSQLEnumJdbcType. Generated DDL would make them VARCHAR and the
--     mapping that production depends on would never be exercised.
--   * api_usage_daily.api_key_id is NOT NULL with the zero-UUID sentinel default (amendment 1). A test
--     that wrote NULL there would pass against generated DDL and fail in production.
--
-- The `messages` ALTER from 020 is deliberately absent: this service does not map messages (hand-off §2).

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================
-- Shared enum types and the updated_at trigger (from 001_core.sql)
-- ============================================================
CREATE TYPE tenant_status AS ENUM ('ACTIVE', 'SUSPENDED', 'INACTIVE');
CREATE TYPE whatsapp_account_status AS ENUM ('ACTIVE', 'INACTIVE', 'DISCONNECTED');

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- ============================================================
-- Shared tables this service reads (never writes)
-- ============================================================
CREATE TABLE admins (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email      VARCHAR(255) NOT NULL UNIQUE,
    full_name  VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tenants (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    company_name  VARCHAR(255) NOT NULL,
    contact_email VARCHAR(255) NOT NULL,
    contact_phone VARCHAR(50),
    address       TEXT,
    status        tenant_status NOT NULL DEFAULT 'ACTIVE',
    onboarded_by  UUID REFERENCES admins(id) ON DELETE SET NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tenant_users (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id  UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email      VARCHAR(255) NOT NULL UNIQUE,
    full_name  VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE whatsapp_accounts (
    id                     UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id              UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    display_name           VARCHAR(255) NOT NULL,
    waba_id                VARCHAR(255) NOT NULL,
    phone_number_id        VARCHAR(255) NOT NULL,
    phone_number           VARCHAR(50)  NOT NULL,
    access_token           TEXT NOT NULL,
    webhook_secret         VARCHAR(255),
    status                 whatsapp_account_status NOT NULL DEFAULT 'ACTIVE',
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by             UUID REFERENCES admins(id) ON DELETE SET NULL,
    -- Added by 020 §7 (token expiry becomes first-class).
    token_expires_at       TIMESTAMP WITH TIME ZONE,
    token_last_verified_at TIMESTAMP WITH TIME ZONE,
    token_error_code       VARCHAR(16)
);

-- ============================================================
-- 020_api_ecosystem.sql — the tables this service owns
-- ============================================================
CREATE TABLE api_plans (
    id                       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code                     VARCHAR(32)  NOT NULL UNIQUE,
    name                     VARCHAR(100) NOT NULL,
    description              TEXT,
    requests_per_minute      INT NOT NULL,
    messages_per_day         INT NOT NULL,
    messages_per_month       INT NOT NULL,
    template_creates_per_day INT NOT NULL DEFAULT 20,
    max_keys                 INT NOT NULL DEFAULT 3,
    max_webhook_endpoints    INT NOT NULL DEFAULT 3,
    overage_policy           VARCHAR(8) NOT NULL DEFAULT 'BLOCK',
    sandbox_only             BOOLEAN NOT NULL DEFAULT false,
    is_default               BOOLEAN NOT NULL DEFAULT false,
    active                   BOOLEAN NOT NULL DEFAULT true,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_api_plans_overage CHECK (overage_policy IN ('BLOCK', 'WARN'))
);
CREATE UNIQUE INDEX uq_api_plans_default ON api_plans (is_default) WHERE is_default = true;
CREATE TRIGGER update_api_plans_updated_at BEFORE UPDATE ON api_plans
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE tenant_api_plans (
    tenant_id   UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    plan_id     UUID NOT NULL REFERENCES api_plans(id),
    assigned_by UUID REFERENCES admins(id) ON DELETE SET NULL,
    starts_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ends_at     TIMESTAMP WITH TIME ZONE,
    overrides   JSONB,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER update_tenant_api_plans_updated_at BEFORE UPDATE ON tenant_api_plans
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE api_partners (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name              VARCHAR(150) NOT NULL,
    slug              VARCHAR(64)  NOT NULL UNIQUE,
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    branding          JSONB,
    billing_mode      VARCHAR(16)  NOT NULL DEFAULT 'CUSTOMER_MANAGED',
    meta_app_id       VARCHAR(64),
    meta_es_config_id VARCHAR(64),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER update_api_partners_updated_at BEFORE UPDATE ON api_partners
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE api_partner_tenants (
    partner_id UUID NOT NULL REFERENCES api_partners(id) ON DELETE CASCADE,
    tenant_id  UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (partner_id, tenant_id)
);

CREATE TABLE api_keys (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    partner_id   UUID REFERENCES api_partners(id) ON DELETE SET NULL,
    name         VARCHAR(100) NOT NULL,
    prefix       VARCHAR(16)  NOT NULL,
    key_hash     VARCHAR(64)  NOT NULL UNIQUE,
    mode         VARCHAR(8)   NOT NULL,
    scopes       JSONB        NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    last_used_at TIMESTAMP WITH TIME ZONE,
    expires_at   TIMESTAMP WITH TIME ZONE,
    revoked_at   TIMESTAMP WITH TIME ZONE,
    ui_session   BOOLEAN      NOT NULL DEFAULT false,   -- 020a
    created_by   UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_api_keys_mode   CHECK (mode IN ('LIVE', 'TEST')),
    CONSTRAINT chk_api_keys_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);
CREATE INDEX idx_api_keys_tenant ON api_keys (tenant_id);
CREATE INDEX idx_api_keys_prefix ON api_keys (prefix);
CREATE INDEX idx_api_keys_ui_session ON api_keys (tenant_id, expires_at) WHERE ui_session;   -- 020a
CREATE TRIGGER update_api_keys_updated_at BEFORE UPDATE ON api_keys
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE api_webhook_endpoints (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id            UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    partner_id           UUID REFERENCES api_partners(id) ON DELETE SET NULL,
    url                  VARCHAR(2048) NOT NULL,
    secret_encrypted     TEXT NOT NULL,
    events               JSONB NOT NULL,
    status               VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_success_at      TIMESTAMP WITH TIME ZONE,
    last_failure_at      TIMESTAMP WITH TIME ZONE,
    created_by           UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_api_webhook_endpoints_status CHECK (status IN ('ACTIVE', 'PAUSED', 'DISABLED'))
);
CREATE INDEX idx_api_webhook_endpoints_tenant ON api_webhook_endpoints (tenant_id);
CREATE TRIGGER update_api_webhook_endpoints_updated_at BEFORE UPDATE ON api_webhook_endpoints
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE api_outbox_events (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id      UUID NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id   UUID NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at   TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_api_outbox_events_unpublished ON api_outbox_events (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_api_outbox_events_tenant ON api_outbox_events (tenant_id, created_at);

CREATE TABLE api_webhook_deliveries (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    endpoint_id     UUID NOT NULL REFERENCES api_webhook_endpoints(id) ON DELETE CASCADE,
    event_id        UUID NOT NULL REFERENCES api_outbox_events(id) ON DELETE CASCADE,
    attempt         INT NOT NULL,
    status          VARCHAR(16) NOT NULL,
    response_code   INT,
    error           VARCHAR(500),
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    delivered_at    TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_api_webhook_deliveries_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'EXHAUSTED'))
);
CREATE INDEX idx_api_webhook_deliveries_due ON api_webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_api_webhook_deliveries_endpoint ON api_webhook_deliveries (endpoint_id, created_at DESC);

CREATE TABLE api_usage_daily (
    tenant_id         UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    api_key_id        UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    day               DATE NOT NULL,
    requests          INT NOT NULL DEFAULT 0,
    rate_limited      INT NOT NULL DEFAULT 0,
    messages_accepted INT NOT NULL DEFAULT 0,
    messages_sent     INT NOT NULL DEFAULT 0,
    messages_failed   INT NOT NULL DEFAULT 0,
    template_creates  INT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, api_key_id, day)
);

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- The seeded plans. FREE is the default a tenant with no assignment falls back to.
INSERT INTO api_plans (code, name, requests_per_minute, messages_per_day, messages_per_month, is_default) VALUES
    ('FREE',     'Free',       60,   200,    2000,    true),
    ('STARTER',  'Starter',    300,  5000,   100000,  false),
    ('BUSINESS', 'Business',   1000, 50000,  1000000, false);

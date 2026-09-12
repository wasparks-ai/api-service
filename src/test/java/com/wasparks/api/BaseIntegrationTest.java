package com.wasparks.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.auth.ApiKeyService;
import com.wasparks.api.entity.ApiKey;
import com.wasparks.api.enums.ApiKeyMode;
import com.wasparks.api.enums.ApiKeyStatus;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.repository.ApiKeyRepository;
import com.wasparks.api.repository.ApiOutboxEventRepository;
import com.wasparks.api.repository.ApiPlanRepository;
import com.wasparks.api.repository.ApiUsageDailyRepository;
import com.wasparks.api.repository.ApiWebhookDeliveryRepository;
import com.wasparks.api.repository.ApiWebhookEndpointRepository;
import com.wasparks.api.repository.TenantApiPlanRepository;
import com.wasparks.api.util.Hashing;
import com.wasparks.api.util.Uuid7;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Shared fixture for the integration tests: a real PostgreSQL and a real Redis.
 *
 * <p><b>Both are real containers, not fakes.</b> Half of what this service does is Redis semantics —
 * atomic Lua reservations, consumer-group redelivery, {@code SETNX} throttles — and an in-memory stand-in
 * either does not implement them or implements them differently, which would make the tests agree with
 * the mock rather than with production. The database is real for the same reason: {@code ddl-auto=validate}
 * against the actual 020 DDL is how a mapping mistake gets caught here instead of at deploy.
 *
 * <p>The containers are static, so they start once for the whole suite; each test truncates instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wasparks_api_test")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/schema-test.sql");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort());

    static {
        // Started manually rather than with @Container so both containers are shared across every test
        // class in the suite. Testcontainers' Ryuk sidecar reaps them when the JVM exits.
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected ApiKeyRepository apiKeyRepository;
    @Autowired
    protected ApiKeyService apiKeyService;
    @Autowired
    protected ApiPlanRepository apiPlanRepository;
    @Autowired
    protected TenantApiPlanRepository tenantApiPlanRepository;
    @Autowired
    protected ApiWebhookEndpointRepository endpointRepository;
    @Autowired
    protected ApiWebhookDeliveryRepository deliveryRepository;
    @Autowired
    protected ApiOutboxEventRepository outboxRepository;
    @Autowired
    protected ApiUsageDailyRepository usageRepository;

    /** The tenant every test sends as, and its ACTIVE WhatsApp number. */
    protected UUID tenantId;
    protected UUID tenantUserId;
    protected String phoneNumberId;

    @BeforeEach
    void resetFixture() {
        // Truncate rather than drop: the schema is the thing under test, so it must survive between
        // tests. CASCADE handles the FK web in one statement.
        jdbcTemplate.execute("""
                TRUNCATE TABLE api_webhook_deliveries, api_outbox_events, api_webhook_endpoints,
                               api_usage_daily, api_keys, tenant_api_plans, api_partner_tenants,
                               whatsapp_accounts, tenant_users, tenants, api_partners, shedlock
                RESTART IDENTITY CASCADE
                """);
        // Redis carries counters and cached principals between tests; a leftover rate-limit window or a
        // cached key would make a later test pass or fail for the wrong reason.
        //
        // The connection is closed explicitly. Leaking one per test exhausts the Lettuce pool partway
        // through a long test class, and because every limiter here deliberately fails OPEN when Redis
        // is unreachable, the symptom is not an error — it is later tests quietly losing their rate
        // limits and quotas and passing or failing for reasons that have nothing to do with the code.
        try (var connection = redis.getRequiredConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }

        tenantId = UUID.randomUUID();
        tenantUserId = UUID.randomUUID();
        phoneNumberId = "1234567890";

        jdbcTemplate.update(
                "INSERT INTO tenants (id, company_name, contact_email, status) VALUES (?, ?, ?, 'ACTIVE'::tenant_status)",
                tenantId, "Test Co", "owner@test.co");
        jdbcTemplate.update(
                "INSERT INTO tenant_users (id, tenant_id, email, full_name) VALUES (?, ?, ?, ?)",
                tenantUserId, tenantId, "owner@test.co", "Test Owner");
        jdbcTemplate.update("""
                        INSERT INTO whatsapp_accounts
                          (id, tenant_id, display_name, waba_id, phone_number_id, phone_number,
                           access_token, status)
                        VALUES (?, ?, 'Test Co', 'waba-1', ?, '+919000000000', 'enc', 'ACTIVE'::whatsapp_account_status)
                        """,
                UUID.randomUUID(), tenantId, phoneNumberId);
    }

    // ------------------------------------------------------------------ fixture helpers

    /** Set the tenant's status, for the "key of a suspended tenant is refused" case. */
    protected void setTenantStatus(String status) {
        jdbcTemplate.update("UPDATE tenants SET status = ?::tenant_status WHERE id = ?",
                status, tenantId);
    }

    protected void setAccountStatus(String status) {
        jdbcTemplate.update(
                "UPDATE whatsapp_accounts SET status = ?::whatsapp_account_status WHERE tenant_id = ?",
                status, tenantId);
    }

    /** Issue a usable LIVE key with every scope, returning the plaintext. */
    protected String issueLiveKey() {
        return apiKeyService.issue(tenantId, tenantUserId, "test key", ApiKeyMode.LIVE, null, null)
                .plaintext();
    }

    protected String issueTestKey() {
        return apiKeyService.issue(tenantId, tenantUserId, "sandbox key", ApiKeyMode.TEST, null, null)
                .plaintext();
    }

    /** Issue a key carrying only the named scopes — the fixture behind the 403 test. */
    protected String issueKeyWithScopes(Scope... scopes) {
        Set<String> wire = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            wire.add(scope.wire());
        }
        return apiKeyService.issue(tenantId, tenantUserId, "scoped key", ApiKeyMode.LIVE, wire, null)
                .plaintext();
    }

    /** Write a raw key row directly, for states the service will not mint (revoked, expired). */
    protected String insertRawKey(ApiKeyStatus status, Instant expiresAt) {
        String plaintext = "wsk_live_" + UUID.randomUUID().toString().replace("-", "");
        ApiKey key = ApiKey.builder()
                .id(Uuid7.generate())
                .tenantId(tenantId)
                .name("raw")
                .prefix(plaintext.substring(0, 16))
                .keyHash(Hashing.sha256Hex(plaintext))
                .mode(ApiKeyMode.LIVE)
                .scopes(Scope.allWire())
                .status(status)
                .expiresAt(expiresAt)
                .build();
        apiKeyRepository.save(key);
        return plaintext;
    }

    /** Assign a plan to the tenant, optionally with overrides, so limit behaviour can be driven. */
    protected void assignPlan(String planCode, String overridesJson) {
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM api_plans WHERE code = ?", UUID.class, planCode);
        jdbcTemplate.update("""
                        INSERT INTO tenant_api_plans (tenant_id, plan_id, overrides)
                        VALUES (?, ?, CAST(? AS jsonb))
                        ON CONFLICT (tenant_id) DO UPDATE
                          SET plan_id = EXCLUDED.plan_id, overrides = EXCLUDED.overrides
                        """,
                tenantId, planId, overridesJson);
    }

    /** Create a bespoke plan row, for the WARN-overage and tight-limit cases. */
    protected void createPlan(String code, int requestsPerMinute, int messagesPerDay,
                              int messagesPerMonth, String overagePolicy, int maxKeys) {
        jdbcTemplate.update("""
                        INSERT INTO api_plans (id, code, name, requests_per_minute, messages_per_day,
                                               messages_per_month, overage_policy, max_keys)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), code, code, requestsPerMinute, messagesPerDay, messagesPerMonth,
                overagePolicy, maxKeys);
    }

    /** A tenant access token of the kind tenants-service issues, for the /v1/keys chain. */
    protected String tenantJwt(String role) {
        SecretKey key = Keys.hmacShaKeyFor(
                "test-tenants-jwt-secret-at-least-32-characters-long-for-hs256"
                        .getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(tenantUserId.toString())
                .claim("tenantId", tenantId.toString())
                .claim("email", "owner@test.co")
                .claim("role", role)
                .issuer("whatsapp-tenants-service")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .signWith(key)
                .compact();
    }
}

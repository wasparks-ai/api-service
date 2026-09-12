package com.wasparks.api.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start in the {@code prod} profile on a development secret (epic §C4, amendment 9).
 *
 * <p>This exists because of a real incident in this estate: the deploy env example set
 * {@code JWT_SECRET} while the service read {@code TENANTS_JWT_SECRET}, so production ran for months
 * signing tenant sessions with the hard-coded default — forgeable by anyone who had seen the repository.
 * Nothing failed, which is precisely why nobody noticed. A loud crash at deploy time is the cheapest
 * possible version of that discovery.
 *
 * <p>All four secrets are checked, not just the JWT one. {@code ENCRYPTION_SECRET} left at its default
 * would make every webhook secret decryptable from the repository; {@code INTERNAL_API_SECRET} left at
 * its default would make the internal surface callable by anyone who can reach the docker network. The
 * cost of being wrong is the same in each case.
 *
 * <p>Dev keeps its fallbacks, so a local run and the integration tests need no environment at all.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class StartupSecretsGuard {

    /** HMAC-SHA256 needs 256 bits; jjwt rejects anything shorter anyway, so this is the floor. */
    static final int MIN_SECRET_LENGTH = 32;

    static final String DEV_JWT_SECRET =
            "tenants-service-256-bit-secret-key-must-be-at-least-32-characters-long";
    static final String DEV_INTERNAL_SECRET = "dev-only-internal-secret-not-for-prod-0123456789";
    static final String DEV_ENCRYPTION_SECRET = "your-aes-256-encryption-key-32c";

    private final Environment environment;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.internal.secret}")
    private String internalSecret;

    @Value("${app.encryption.secret-key}")
    private String encryptionSecret;

    @PostConstruct
    public void verify() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        List<String> problems = new ArrayList<>();
        check(problems, "TENANTS_JWT_SECRET", jwtSecret, DEV_JWT_SECRET, MIN_SECRET_LENGTH);
        check(problems, "INTERNAL_API_SECRET", internalSecret, DEV_INTERNAL_SECRET, MIN_SECRET_LENGTH);
        // The AES key is padded or truncated to 32 bytes, so a short one is weak rather than rejected —
        // but it must still not be the value printed in the repository.
        check(problems, "ENCRYPTION_SECRET", encryptionSecret, DEV_ENCRYPTION_SECRET, 16);

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start in the prod profile — " + String.join("; ", problems)
                            + ". Set these in deploy/wasparks/env/api-service.env. "
                            + "TENANTS_JWT_SECRET and ENCRYPTION_SECRET must match tenants-service "
                            + "exactly, and INTERNAL_API_SECRET must match tenants-service's own.");
        }
        log.info("Prod secret guard passed: all shared secrets are set and are not development defaults.");
    }

    private void check(List<String> problems, String name, String value, String devDefault, int minLength) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is not set");
        } else if (devDefault.equals(value)) {
            problems.add(name + " is still the built-in development default");
        } else if (value.length() < minLength) {
            problems.add(name + " is shorter than " + minLength + " characters");
        }
    }
}

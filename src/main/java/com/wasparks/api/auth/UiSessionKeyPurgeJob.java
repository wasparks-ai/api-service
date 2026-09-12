package com.wasparks.api.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes expired UI-session keys once a day (epic §D.3).
 *
 * <p>Housekeeping, not security: a UI-session key stops authenticating the moment it passes
 * {@code expires_at}, because {@link com.wasparks.api.entity.ApiKey#isUsable} checks it and the resolved
 * principal is cached for five minutes at most. What this prevents is the table growing by one dead row
 * per dashboard visit, forever — rows nothing lists and nobody would ever think to clean up by hand.
 *
 * <p>ShedLock'd because it is a DELETE: two replicas racing would be harmless in outcome (the second
 * finds nothing) but would each hold a lock on the same rows, and this is exactly the kind of job where
 * "harmless in outcome" is worth not relying on.
 *
 * <p>03:30 rather than midnight — off the hour, and clear of the usage flush's day boundary.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UiSessionKeyPurgeJob {

    private final ApiKeyService apiKeyService;

    @Scheduled(cron = "${app.api.ui-session.purge-cron}")
    @SchedulerLock(name = "api-ui-session-key-purge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void purge() {
        try {
            apiKeyService.purgeExpiredUiSessionKeys();
        } catch (Exception e) {
            // Tomorrow's run picks up whatever is left; a failed purge is never urgent.
            log.error("UI-session key purge failed", e);
        }
    }
}

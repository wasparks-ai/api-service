package com.wasparks.api.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;

/**
 * Scheduled work, and the lock that keeps it single-instance.
 *
 * <p>Three jobs run here: {@code OutboxPoller} (2s), {@code WebhookDispatcher} (1s) and
 * {@code UsageFlushJob} (10 min). All three are <b>externally visible</b> if they double-run — two
 * replicas would deliver every webhook twice to the customer's endpoint and double-count the usage a
 * customer is billed on. ShedLock on the shared {@code shedlock} table (migration 020, the same table
 * tenants-service uses) is what makes adding a replica a deployment decision rather than a bug.
 *
 * <p>{@code usingDbTime()} takes the lock clock from PostgreSQL, so skew between two VMs cannot let both
 * believe the lock is free.
 *
 * <p>The scheduler gets its own pool sized to the job count. Spring's default is a single thread, which
 * would let one slow webhook batch hold up the outbox poller behind it.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("api-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        return scheduler;
    }
}

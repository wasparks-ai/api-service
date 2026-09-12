package com.wasparks.api.auth;

import com.wasparks.api.enums.Scope;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The scope a handler requires, enforced by {@code ScopeInterceptor}.
 *
 * <p><b>Why this exists alongside {@code @PreAuthorize}.</b> The epic specifies the scope check as
 * {@code @PreAuthorize("hasAuthority('SCOPE_messages:send')")}, and that annotation is kept — it is the
 * authoritative gate, evaluated by Spring Security at the method itself. But method security runs
 * <em>after</em> every {@code HandlerInterceptor}, and the epic also fixes the pipeline order as
 * rate limit → scope → idempotency → quota (hand-off §4). Left to {@code @PreAuthorize} alone, a request
 * with the wrong scope would first consume an idempotency record and reserve message quota, then be
 * refused — so a caller with a mis-scoped key could burn a tenant's daily allowance without ever sending
 * anything.
 *
 * <p>So the check happens twice, on purpose: here for ordering, and at the method for authority. The two
 * cannot drift, because {@code ScopeAnnotationContractTest} walks every handler and fails the build if a
 * method carries one without the other or the values disagree.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiredScope {

    Scope value();
}

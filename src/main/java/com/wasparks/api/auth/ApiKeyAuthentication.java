package com.wasparks.api.auth;

import com.wasparks.api.enums.Scope;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * The {@code Authentication} for a key-authenticated request. Its authorities are the key's scopes with
 * the {@code SCOPE_} prefix, which is what lets a controller write
 * {@code @PreAuthorize("hasAuthority('SCOPE_messages:send')")}.
 *
 * <p>Already authenticated on construction: the filter has verified the key against the database before
 * it builds this, so there is no {@code AuthenticationProvider} round trip to make.
 */
public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final transient ApiPrincipal principal;

    public ApiKeyAuthentication(ApiPrincipal principal) {
        super(toAuthorities(principal));
        this.principal = principal;
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> toAuthorities(ApiPrincipal principal) {
        return principal.scopes().stream()
                .map(s -> (GrantedAuthority) new SimpleGrantedAuthority(Scope.AUTHORITY_PREFIX + s))
                .toList();
    }

    @Override
    public Object getCredentials() {
        return null;   // the key itself is never retained past authentication
    }

    @Override
    public ApiPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return "api-key:" + principal.keyId();
    }
}

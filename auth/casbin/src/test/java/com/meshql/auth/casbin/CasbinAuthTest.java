package com.meshql.auth.casbin;

import com.meshql.auth.token.Identity;
import com.meshql.auth.token.TokenMark;
import com.meshql.auth.token.TokenSession;
import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.Operation;
import com.tailoredshapes.stash.Stash;
import org.casbin.jcasbin.main.Enforcer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CasbinAuth.
 */
@ExtendWith(MockitoExtension.class)
class CasbinAuthTest {

    @Mock
    private Identity mockIdentity;

    private Enforcer enforcer;
    private CasbinAuth casbinAuth;

    @BeforeEach
    void setUp() {
        // Load test model and policy from resources
        String modelPath = getClass().getClassLoader().getResource("model.conf").getPath();
        String policyPath = getClass().getClassLoader().getResource("policy.csv").getPath();

        enforcer = new Enforcer(modelPath, policyPath);
        casbinAuth = new CasbinAuth(enforcer, mockIdentity);
    }

    /** The tokens the session resolved for the caller. */
    private List<String> tokensOf(AuthSession session) {
        return ((TokenSession) session).tokens();
    }

    private Envelope marked(List<String> tokens) {
        return new Envelope("id", new Stash(), Instant.now(), false, TokenMark.of(tokens));
    }

    // ==================== authenticate tests ====================

    @Test
    void authenticate_shouldReturnRoles_forUserWithRoles() {
        // user1 has role 'admin' in policy.csv
        when(mockIdentity.resolve(any())).thenReturn(List.of("user1"));

        List<String> roles = tokensOf(casbinAuth.authenticate(new Stash()));

        assertEquals(1, roles.size());
        assertTrue(roles.contains("admin"));
    }

    @Test
    void authenticate_shouldReturnEditorRole_forUser2() {
        // user2 has role 'editor' in policy.csv
        when(mockIdentity.resolve(any())).thenReturn(List.of("user2"));

        List<String> roles = tokensOf(casbinAuth.authenticate(new Stash()));

        assertEquals(1, roles.size());
        assertTrue(roles.contains("editor"));
    }

    @Test
    void authenticate_shouldReturnViewerRole_forUser3() {
        // user3 has role 'viewer' in policy.csv
        when(mockIdentity.resolve(any())).thenReturn(List.of("user3"));

        List<String> roles = tokensOf(casbinAuth.authenticate(new Stash()));

        assertEquals(1, roles.size());
        assertTrue(roles.contains("viewer"));
    }

    @Test
    void authenticate_shouldReturnEmptyList_whenIdentityReturnsEmpty() {
        when(mockIdentity.resolve(any())).thenReturn(List.of());

        assertTrue(tokensOf(casbinAuth.authenticate(new Stash())).isEmpty());
    }

    @Test
    void authenticate_shouldReturnEmptyList_whenIdentityReturnsNull() {
        when(mockIdentity.resolve(any())).thenReturn(null);

        assertTrue(tokensOf(casbinAuth.authenticate(new Stash())).isEmpty());
    }

    @Test
    void authenticate_shouldReturnEmptyList_forUnknownUser() {
        // unknown-user has no roles in policy.csv
        when(mockIdentity.resolve(any())).thenReturn(List.of("unknown-user"));

        assertTrue(tokensOf(casbinAuth.authenticate(new Stash())).isEmpty());
    }

    // ==================== isAuthorized tests ====================

    @Test
    void isAuthorized_shouldReturnTrue_whenTheEnvelopeCarriesNoMark() {
        Envelope envelope = new Envelope("id", new Stash(), Instant.now(), false, null);

        assertTrue(new TokenSession(List.of("admin")).isAuthorized(Operation.READ, envelope));
    }

    @Test
    void isAuthorized_shouldReturnTrue_whenTheMarkIsEmpty() {
        assertTrue(new TokenSession(List.of("admin")).isAuthorized(Operation.READ, marked(List.of())));
    }

    @Test
    void isAuthorized_shouldReturnTrue_whenRoleMatchesTheMark() {
        assertTrue(new TokenSession(List.of("admin"))
                .isAuthorized(Operation.READ, marked(List.of("admin", "editor"))));
    }

    @Test
    void isAuthorized_shouldReturnTrue_whenAnyRoleMatches() {
        assertTrue(new TokenSession(List.of("viewer", "editor"))
                .isAuthorized(Operation.READ, marked(List.of("admin", "editor"))));
    }

    @Test
    void isAuthorized_shouldReturnFalse_whenNoRoleMatches() {
        assertFalse(new TokenSession(List.of("viewer"))
                .isAuthorized(Operation.READ, marked(List.of("admin", "editor"))));
    }

    @Test
    void isAuthorized_shouldReturnFalse_whenTheCallerHoldsNoTokens() {
        assertFalse(new TokenSession(List.of())
                .isAuthorized(Operation.READ, marked(List.of("admin"))));
    }

    // ==================== Factory method tests ====================

    @Test
    void create_shouldCreateCasbinAuthFromPaths() {
        String modelPath = getClass().getClassLoader().getResource("model.conf").getPath();
        String policyPath = getClass().getClassLoader().getResource("policy.csv").getPath();

        CasbinAuth auth = CasbinAuth.create(modelPath, policyPath, mockIdentity);

        assertNotNull(auth);
        assertNotNull(auth.getEnforcer());
    }

    @Test
    void getEnforcer_shouldReturnEnforcer() {
        assertNotNull(casbinAuth.getEnforcer());
        assertEquals(enforcer, casbinAuth.getEnforcer());
    }

    @Test
    void systemSession_shouldSeeEverything() {
        assertTrue(casbinAuth.systemSession()
                .isAuthorized(Operation.READ, marked(List.of("nobody-holds-this"))));
    }
}

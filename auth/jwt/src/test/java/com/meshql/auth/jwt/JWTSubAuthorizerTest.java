package com.meshql.auth.jwt;

import com.meshql.auth.token.TokenMark;
import com.meshql.auth.token.TokenSession;
import com.meshql.core.Envelope;
import com.meshql.core.Operation;
import com.tailoredshapes.stash.Stash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JWTSubAuthorizer
 */
class JWTSubAuthorizerTest {
    private JWTSubAuthorizer authorizer;
    private static final String TEST_SUB = "test-user";

    @BeforeEach
    void setUp() {
        authorizer = new JWTSubAuthorizer();
    }

    /**
     * Create a JWT token with the given subject.
     * Note: This creates a valid JWT structure but with no signature verification
     * (signature is just a placeholder), which matches our use case.
     */
    private String createTestToken(String subject) {
        // Header: {"alg":"HS256","typ":"JWT"}
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());

        // Payload with sub claim
        String payloadJson = String.format("{\"sub\":\"%s\",\"iat\":1234567890}", subject);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes());

        // Signature (placeholder - we don't verify signatures)
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("signature".getBytes());

        return header + "." + payload + "." + signature;
    }

    // ==================== authenticate tests ====================

    @Test
    void authenticate_shouldReturnEmptyList_whenNoHeaders() {
        Stash context = new Stash();

        List<String> result = authorizer.resolve(context);

        assertTrue(result.isEmpty());
    }

    @Test
    void authenticate_shouldReturnEmptyList_whenHeadersIsNull() {
        Stash context = new Stash();
        context.put("headers", null);

        List<String> result = authorizer.resolve(context);

        assertTrue(result.isEmpty());
    }

    @Test
    void authenticate_shouldReturnEmptyList_whenNoAuthorizationHeader() {
        Stash context = new Stash();
        context.put("headers", Map.of("content-type", "application/json"));

        List<String> result = authorizer.resolve(context);

        assertTrue(result.isEmpty());
    }

    @Test
    void authenticate_shouldReturnEmptyList_whenAuthorizationIsNotBearer() {
        Stash context = new Stash();
        context.put("headers", Map.of("authorization", "Basic sometoken"));

        List<String> result = authorizer.resolve(context);

        assertTrue(result.isEmpty());
    }

    @Test
    void authenticate_shouldExtractSubject_fromValidBearerToken() {
        String token = createTestToken(TEST_SUB);
        Stash context = new Stash();
        context.put("headers", Map.of("authorization", "Bearer " + token));

        List<String> result = authorizer.resolve(context);

        assertEquals(1, result.size());
        assertEquals(TEST_SUB, result.get(0));
    }

    @Test
    void authenticate_shouldWorkWithStashHeaders() {
        String token = createTestToken(TEST_SUB);
        Stash headers = new Stash();
        headers.put("authorization", "Bearer " + token);

        Stash context = new Stash();
        context.put("headers", headers);

        List<String> result = authorizer.resolve(context);

        assertEquals(1, result.size());
        assertEquals(TEST_SUB, result.get(0));
    }

    @Test
    void authenticate_shouldReturnEmptyList_whenTokenHasInvalidFormat() {
        Stash context = new Stash();
        context.put("headers", Map.of("authorization", "Bearer invalid-token-no-dots"));

        List<String> result = authorizer.resolve(context);

        assertTrue(result.isEmpty());
    }

    @Test
    void authenticate_shouldReturnEmptyList_whenTokenPayloadIsInvalidJson() {
        // Create token with invalid base64 payload
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\"}".getBytes());
        String invalidPayload = "not-valid-base64!!!";
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("sig".getBytes());

        String token = header + "." + invalidPayload + "." + signature;

        Stash context = new Stash();
        context.put("headers", Map.of("authorization", "Bearer " + token));

        List<String> result = authorizer.resolve(context);

        assertTrue(result.isEmpty());
    }

    @Test
    void authenticate_shouldReturnEmptyList_whenTokenHasNoSubClaim() {
        // Create token without sub claim
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iat\":1234567890}".getBytes());
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("sig".getBytes());

        String token = header + "." + payload + "." + signature;

        Stash context = new Stash();
        context.put("headers", Map.of("authorization", "Bearer " + token));

        List<String> result = authorizer.resolve(context);

        assertTrue(result.isEmpty());
    }

    // ==================== isAuthorized tests ====================

    private Envelope marked(java.util.List<String> tokens) {
        return new Envelope("id", new Stash(), Instant.now(), false, TokenMark.of(tokens));
    }

    @Test
    void isAuthorized_shouldReturnTrue_whenTheEnvelopeCarriesNoMark() {
        Envelope envelope = new Envelope("id", new Stash(), Instant.now(), false, null);

        assertTrue(authorizer.authenticate(new Stash()).isAuthorized(Operation.READ, envelope));
    }

    @Test
    void isAuthorized_shouldReturnTrue_whenTheMarkIsEmpty() {
        assertTrue(new TokenSession(List.of("some-cred"))
                .isAuthorized(Operation.READ, marked(List.of())));
    }

    @Test
    void isAuthorized_shouldReturnTrue_whenCredentialsMatchTheMark() {
        assertTrue(new TokenSession(List.of("user1"))
                .isAuthorized(Operation.READ, marked(List.of("user1", "user2"))));
    }

    @Test
    void isAuthorized_shouldReturnTrue_whenAnyCredentialMatches() {
        assertTrue(new TokenSession(List.of("other", "user2"))
                .isAuthorized(Operation.READ, marked(List.of("user1", "user2"))));
    }

    @Test
    void isAuthorized_shouldReturnFalse_whenNoCredentialsMatch() {
        assertFalse(new TokenSession(List.of("user3"))
                .isAuthorized(Operation.READ, marked(List.of("user1", "user2"))));
    }

    @Test
    void isAuthorized_shouldReturnFalse_whenTheCallerHoldsNoTokens() {
        assertFalse(new TokenSession(List.of())
                .isAuthorized(Operation.READ, marked(List.of("user1"))));
    }

    @Test
    void stamp_shouldMarkTheRecordWithTheCallersOwnTokens() {
        Envelope stamped = new TokenSession(List.of("user1"))
                .stamp(new Envelope("id", new Stash(), Instant.now(), false, null));

        assertEquals(List.of("user1"), TokenMark.tokensOf(stamped));
    }

    @Test
    void stamp_shouldLeaveAnUncredentialedWriteMarkless() {
        Envelope stamped = authorizer.authenticate(new Stash())
                .stamp(new Envelope("id", new Stash(), Instant.now(), false, null));

        assertTrue(TokenMark.tokensOf(stamped).isEmpty());
    }

    @Test
    void systemSession_shouldSeeEverything() {
        assertTrue(authorizer.systemSession()
                .isAuthorized(Operation.READ, marked(List.of("nobody-holds-this"))));
    }
}

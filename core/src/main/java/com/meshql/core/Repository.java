package com.meshql.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage for envelopes.
 *
 * <p>No method here takes credentials. Every method takes an {@link AuthSession}
 * instead: an opaque handle the adapter cannot read, only ask. On write the
 * adapter calls {@link AuthSession#stamp} and persists what comes back, in the
 * same write as the payload. On read it fetches, then asks
 * {@link AuthSession#isAuthorized}.
 *
 * <p>A null session fails closed. It is never "an internal call".
 */
public interface Repository {
    Envelope create(Envelope envelope, AuthSession session);
    Optional<Envelope> read(String id, AuthSession session, Instant createdAt);
    List<Envelope> list(AuthSession session);
    Boolean remove(String id, AuthSession session);
    List<Envelope> createMany(List<Envelope> payloads, AuthSession session);
    List<Envelope> readMany(List<String> ids, AuthSession session);
    Map<String, Boolean> removeMany(List<String> ids, AuthSession session);
}

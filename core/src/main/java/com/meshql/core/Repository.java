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

    /**
     * Every version of one document, oldest first.
     *
     * <p>A version the caller cannot read still appears, carrying when it happened
     * and whether it was a deletion and no token. Omitting it would make the
     * history look continuous when it is not.
     *
     * <p>There is no default implementation, and that absence is the point. A
     * default returning "unsupported" lets an adapter fall out of conformance with
     * nothing saying so; a compile error says so.
     *
     * @see Versions#listing(List, AuthSession)
     */
    List<VersionRef> listVersions(String id, AuthSession session);

    /**
     * One version of a document, addressed by its token.
     *
     * <p>Authorization is the same question {@link #read} asks. An unknown token is
     * empty; a token naming a version this caller may not read is a
     * {@link NotAuthorizedException}, because the listing already told them it
     * exists.
     *
     * @see Versions#resolve(List, String, AuthSession)
     */
    Optional<Envelope> readVersion(String id, String token, AuthSession session);
}

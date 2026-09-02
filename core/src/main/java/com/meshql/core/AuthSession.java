package com.meshql.core;

/**
 * One request's worth of authorization, resolved by the auth plugin.
 *
 * <p>A session is what the plugin hands back from {@link Auth#authenticate}: it
 * holds whatever that plugin needs to answer for the caller, and it is the only
 * thing entitled to answer. Storage never inspects it, never inspects what it
 * stamped, and holds no credentials of its own.
 *
 * <p><b>There is no unset session.</b> A caller outside a request - a worker, a
 * migration, a certification harness reading storage directly - asks the plugin
 * for {@link Auth#systemSession()} and says so. An absent session fails closed.
 * "No session means allow" is the bypass this interface exists to remove: every
 * Java backend once read an empty caller-token list as "internal read, do not
 * filter", which let an anonymous caller read everything.
 *
 * <p>A session is request-scoped and must not be shared across requests. It is
 * passed down to storage as a handle, in the parameter position the old
 * {@code List<String> tokens} occupied.
 */
public interface AuthSession {
    /**
     * Return the envelope storage should persist, carrying whatever this plugin
     * needs recorded against the record.
     *
     * <p>The plugin decides what goes in {@link Envelope#auth()}; storage writes
     * it verbatim, inside the same write as the payload, and never reads it. A
     * plugin that needs nothing persisted returns the envelope unchanged.
     */
    Envelope stamp(Envelope envelope);

    /**
     * Whether this caller may perform {@code operation} on {@code envelope}.
     *
     * <p>Storage fetches, then asks. It does not translate the mark into a query
     * predicate, because an opaque mark cannot build one.
     */
    boolean isAuthorized(Operation operation, Envelope envelope);
}

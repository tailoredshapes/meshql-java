package com.meshql.core;

import com.tailoredshapes.stash.Stash;

/**
 * The auth plugin. It is the only thing in meshql entitled to answer "can this
 * requester see this record, or create it".
 *
 * <p>The surface is deliberately two methods and nothing else. There is no
 * helper here that lets a caller work out the answer without asking - no
 * "are these tokens visible to those tokens", no "does this caller see
 * everything" - because every such helper is an invitation for a storage
 * adapter to answer for itself, and eleven of them did, eleven different ways.
 *
 * <p>The rules that used to live in framework code - that {@code "*"} means
 * "sees everything", that an envelope with an empty mark is public, and that a
 * write copies the caller's tokens onto the envelope - now live inside whichever
 * plugin wants them. A plugin that authorizes on a payload field holds no tokens
 * at all and is none the worse for it.
 */
public interface Auth {
    /**
     * Resolve the caller from a request and return their session.
     *
     * @param requestContext the portable map an edge lifts from trusted identity
     *                       headers - today's AuthContext - rather than a raw
     *                       HTTP request, which does not port across runtimes
     * @return a session, never null. A plugin that cannot identify the caller
     *         returns a session that answers for an anonymous one, not null.
     */
    AuthSession authenticate(Stash requestContext);

    /**
     * The session a caller outside a request uses.
     *
     * <p>A worker, a change feed, a migration or a certification harness reading
     * storage directly is entitled to everything - but has to say so, with this,
     * rather than by presenting nothing and being waved through.
     */
    AuthSession systemSession();
}

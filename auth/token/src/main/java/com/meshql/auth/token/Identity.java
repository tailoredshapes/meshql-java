package com.meshql.auth.token;

import com.tailoredshapes.stash.Stash;

import java.util.List;

/**
 * Resolves a request to the tokens its caller holds.
 *
 * <p>This is deliberately not an {@link com.meshql.core.Auth}. A plugin that
 * layers on another - Casbin resolving a JWT subject to roles - needs the
 * caller's identity, and an {@code Auth} hands back an opaque session that has
 * no identity to read. Composing at this level keeps the session opaque and
 * keeps identity resolution where it belongs: below the plugin, not inside it.
 */
@FunctionalInterface
public interface Identity {
    /** The tokens this request's caller holds, or an empty list for none. */
    List<String> resolve(Stash requestContext);
}

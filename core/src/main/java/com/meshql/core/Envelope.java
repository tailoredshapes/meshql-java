package com.meshql.core;

import com.tailoredshapes.stash.Stash;

import java.time.Instant;

/**
 * A stored record and its metadata.
 *
 * @param auth the auth plugin's mark: an opaque, plugin-owned value that storage
 *             persists verbatim, inside the same write as the payload, and never
 *             reads. It used to be a {@code List<String>} of authorized tokens,
 *             which is a transparent type carrying a rule - anyone holding one
 *             could re-derive the rule, and everyone did. Only the plugin that
 *             stamped it may interpret it. May be null, meaning the plugin
 *             stamped nothing.
 */
public record Envelope(
        String id,
        Stash payload,
        Instant createdAt,
        boolean deleted,
        Stash auth
){}

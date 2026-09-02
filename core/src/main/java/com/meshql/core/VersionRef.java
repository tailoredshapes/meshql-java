package com.meshql.core;

import java.time.Instant;

/**
 * One version of a document, as it appears in a version listing.
 *
 * @param token     addresses this version, or {@code null} when the caller is not
 *                  authorized to read it. An unreadable version still appears in a
 *                  listing, because omitting it would make the history look
 *                  continuous when it is not.
 * @param createdAt when the version was written
 * @param deleted   whether this version is the deletion of the document
 */
public record VersionRef(String token, Instant createdAt, boolean deleted) {

    /** A version this caller may read, addressed by its token. */
    public static VersionRef visible(Envelope envelope) {
        return new VersionRef(Versions.versionToken(envelope), envelope.createdAt(), envelope.deleted());
    }

    /**
     * A version the caller cannot read. Carries when it happened and whether it was
     * a deletion, and nothing else.
     */
    public static VersionRef tombstone(Envelope envelope) {
        return new VersionRef(null, envelope.createdAt(), envelope.deleted());
    }

    public boolean authorized() {
        return token != null;
    }
}

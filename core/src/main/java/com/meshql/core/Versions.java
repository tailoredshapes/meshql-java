package com.meshql.core;

import com.tailoredshapes.stash.Stash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Addressing one version of a document.
 *
 * <p>meshql keeps every version of every document, but {@code created_at} is
 * millisecond precision, so a timestamp cannot serve as a version's address: two
 * versions of one document can tie.
 *
 * <p>The token here closes that. It is derived from the fields that identify a
 * version rather than from anything the store provides, for two reasons.
 * Postgres and MySQL carry only the five envelope columns and have no stable
 * per-row key to borrow, so a natural key would mean a migration on every
 * deployment. And a content-derived token survives a move between adapters:
 * migrate SQLite to Postgres and every version URL still resolves.
 *
 * <p>The derivation is a cross-implementation contract, not an implementation
 * detail. Java, Rust and TypeScript must produce the same hex string for the
 * same version, so every step below is pinned: SHA-256 over the document id, the
 * timestamp in epoch milliseconds as a big-endian signed 64-bit integer, one
 * byte for the deletion flag, the sorted authorization mark as a compact JSON
 * array, and the payload as compact JSON with every object's keys sorted
 * recursively — each separated by a single {@code 0x1f} byte.
 *
 * <p>The key sort has to be at every depth and it has to be explicit. Sorting
 * only the top level makes a version's address depend on how a map happened to
 * be built, and leaving the sort to whatever the JSON library does by default is
 * how Rust ended up with a token that changed depending on what else was linked
 * into the binary. {@link #canonicalJson} therefore sorts at every level itself,
 * and leaves arrays alone: order inside an array is data, order between an
 * object's keys is not.
 */
public final class Versions {

    /** The byte between two fields of the hash input. */
    private static final byte SEPARATOR = 0x1f;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Versions() {
    }

    /**
     * Derive a version's token.
     *
     * <p>Two versions collide only when the document id, the timestamp, the
     * deletion flag, the authorization mark and the payload are all identical —
     * which makes them the same version recorded twice, indistinguishable by any
     * other means.
     */
    public static String versionToken(Envelope envelope) {
        MessageDigest digest = sha256();

        digest.update(envelope.id() == null
                ? new byte[0]
                : envelope.id().getBytes(StandardCharsets.UTF_8));
        digest.update(SEPARATOR);
        digest.update(bigEndian(envelope.createdAt() == null ? 0L : envelope.createdAt().toEpochMilli()));
        digest.update(SEPARATOR);
        digest.update((byte) (envelope.deleted() ? 1 : 0));
        digest.update(SEPARATOR);
        digest.update(canonicalJson(markParts(envelope.auth())).getBytes(StandardCharsets.UTF_8));
        digest.update(SEPARATOR);
        digest.update(canonicalJson(envelope.payload() == null ? new Stash() : envelope.payload())
                .getBytes(StandardCharsets.UTF_8));

        return hex(digest.digest());
    }

    /**
     * Order versions of one document: oldest first, then undeleted before deleted,
     * then by token.
     *
     * <p>The middle key is not decoration. A write and the delete that buries it
     * land in the same millisecond routinely, and a deletion is by definition later
     * than the version it deletes; without that key the tombstone sorts by hash and
     * lands anywhere in the history.
     *
     * <p>The final tiebreak is arbitrary but identical on every adapter and stable
     * across replays, which is what makes a version list reproducible. A physical
     * row id — SQLite's {@code rowid}, a log offset — would be truer to insertion
     * order on the two adapters that have one and meaningless on the four that do
     * not.
     */
    public static int compare(Envelope a, Envelope b) {
        int byTime = Long.compare(a.createdAt().toEpochMilli(), b.createdAt().toEpochMilli());
        if (byTime != 0) {
            return byTime;
        }
        int byDeletion = Boolean.compare(a.deleted(), b.deleted());
        if (byDeletion != 0) {
            return byDeletion;
        }
        return versionToken(a).compareTo(versionToken(b));
    }

    /** {@link #compare} as a comparator, for sorting a document's versions. */
    public static final Comparator<Envelope> ORDER = Versions::compare;

    /**
     * Turn one document's versions into the listing a caller sees.
     *
     * <p>A version the caller cannot read still appears, carrying when it happened
     * and whether it was a deletion and nothing else. Omitting it would make the
     * history look continuous when it is not.
     *
     * @param versions every stored version of one document, in any order
     * @param session  the caller, which is asked about each version in turn
     */
    public static List<VersionRef> listing(List<Envelope> versions, AuthSession session) {
        List<Envelope> ordered = new ArrayList<>(versions);
        ordered.sort(ORDER);

        List<VersionRef> refs = new ArrayList<>(ordered.size());
        for (Envelope version : ordered) {
            refs.add(session != null && session.isAuthorized(Operation.READ, version)
                    ? VersionRef.visible(version)
                    : VersionRef.tombstone(version));
        }
        return refs;
    }

    /**
     * Resolve one version of a document by its token.
     *
     * @return the version, or empty when no version of this document has that token
     * @throws NotAuthorizedException when the version exists and the caller may not
     *                                read it. Unauthorized is not the same as absent:
     *                                the listing already told the caller it exists,
     *                                and answering "not found" would contradict the
     *                                answer they were just given.
     */
    public static Optional<Envelope> resolve(List<Envelope> versions, String token, AuthSession session) {
        for (Envelope version : versions) {
            if (!versionToken(version).equals(token)) {
                continue;
            }
            if (session == null || !session.isAuthorized(Operation.READ, version)) {
                throw new NotAuthorizedException("Not authorized to read this version");
            }
            return Optional.of(version);
        }
        return Optional.empty();
    }

    /**
     * The parts of an authorization mark, sorted.
     *
     * <p>The mark is opaque to everything but the plugin that stamped it, yet the
     * token has to depend on it: two versions differing only in who may read them
     * are different versions. So this takes the mark's string leaves, wherever
     * they sit in it, which reduces the default plugin's {@code {"tokens":
     * ["alice"]}} to the {@code ["alice"]} every implementation hashes. A plugin
     * that stamps nothing contributes an empty array.
     */
    static List<String> markParts(Stash auth) {
        List<String> parts = new ArrayList<>();
        collectLeaves(auth, parts);
        Collections.sort(parts);
        return parts;
    }

    private static void collectLeaves(Object value, List<String> into) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object entry : map.values()) {
                collectLeaves(entry, into);
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                collectLeaves(entry, into);
            }
        } else {
            into.add(value.toString());
        }
    }

    /**
     * Compact JSON with every object's keys sorted, recursively.
     *
     * <p>Array order is data — {@code [1,2]} is not {@code [2,1]} — so it is left
     * alone. Only maps are reordered, and every nested map too: a version written
     * with a nested object's keys in a different order has to address the same, or
     * a version URL stops resolving after a round trip through anything that
     * reorders a map.
     */
    static String canonicalJson(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            writeNumber(n, out);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, out);
        } else if (value instanceof Collection<?> collection) {
            writeArray(collection, out);
        } else if (value instanceof Object[] array) {
            writeArray(java.util.Arrays.asList(array), out);
        } else {
            // Anything else - a temporal type, an enum, a domain object - is
            // hashed as the text it renders to, which is what a JSON round trip
            // would have made of it anyway.
            writeString(value.toString(), out);
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out) {
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sorted.put(String.valueOf(entry.getKey()), entry.getValue());
        }

        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(entry.getKey(), out);
            out.append(':');
            write(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeArray(Collection<?> values, StringBuilder out) {
        out.append('[');
        boolean first = true;
        for (Object value : values) {
            if (!first) {
                out.append(',');
            }
            first = false;
            write(value, out);
        }
        out.append(']');
    }

    /**
     * A number as JSON writes it.
     *
     * <p>Integers are plain decimal. A floating-point value that happens to be a
     * whole number keeps its {@code .0}, because that is what a JSON serializer
     * emits for a float and dropping it would make {@code 1.0} and {@code 1} the
     * same version.
     */
    private static void writeNumber(Number n, StringBuilder out) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                out.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e16) {
                out.append((long) d).append(".0");
            } else {
                out.append(Double.toString(d).replace("E", "e"));
            }
        } else {
            out.append(n.toString());
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(HEX[(c >> 4) & 0xf]).append(HEX[c & 0xf]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static byte[] bigEndian(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xff);
            value >>>= 8;
        }
        return bytes;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        return out.toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to address a version", e);
        }
    }
}

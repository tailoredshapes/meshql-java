package com.meshql.core;

import com.tailoredshapes.stash.Stash;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The version-token contract, mirrored from {@code meshql-core/src/versions.rs}.
 *
 * <p>These are the facts that make a version URL portable: the same version
 * addresses the same way twice, two writes in one millisecond stay
 * distinguishable, and the address survives any reordering of a map while
 * respecting the order of an array.
 */
class VersionsTest {

    private static Envelope env(String id, long millis, Stash payload) {
        return new Envelope(id, payload, Instant.ofEpochMilli(millis), false, mark("*"));
    }

    private static Stash mark(String... tokens) {
        Stash auth = new Stash();
        auth.put("tokens", List.of(tokens));
        return auth;
    }

    private static Stash payload(Object... pairs) {
        Stash stash = new Stash();
        for (int i = 0; i < pairs.length; i += 2) {
            stash.put((String) pairs[i], pairs[i + 1]);
        }
        return stash;
    }

    @Test
    void theSameVersionHashesTheSameEveryTime() {
        Envelope a = env("d1", 1000, payload("name", "Auth", "tier", "prod"));
        Envelope b = env("d1", 1000, payload("name", "Auth", "tier", "prod"));
        assertEquals(Versions.versionToken(a), Versions.versionToken(b));
    }

    /**
     * The case the whole design exists for: two writes in one millisecond are
     * distinguishable, where a timestamp alone cannot tell them apart.
     */
    @Test
    void twoVersionsInOneMillisecondHaveDistinctTokens() {
        Envelope a = env("d1", 1000, payload("name", "Auth"));
        Envelope b = env("d1", 1000, payload("name", "Auth Service"));
        assertEquals(a.createdAt(), b.createdAt());
        assertNotEquals(Versions.versionToken(a), Versions.versionToken(b));
    }

    @Test
    void aDifferentDocumentAtTheSameInstantDiffers() {
        Envelope a = env("d1", 1000, payload("name", "Auth"));
        Envelope b = env("d2", 1000, payload("name", "Auth"));
        assertNotEquals(Versions.versionToken(a), Versions.versionToken(b));
    }

    @Test
    void aDeletionDiffersFromTheVersionItDeletes() {
        Envelope a = env("d1", 1000, payload("name", "Auth"));
        Envelope b = new Envelope(a.id(), a.payload(), a.createdAt(), true, a.auth());
        assertNotEquals(Versions.versionToken(a), Versions.versionToken(b));
    }

    @Test
    void aDifferentAuthorizationMarkDiffers() {
        Envelope a = new Envelope("d1", payload("name", "Auth"), Instant.ofEpochMilli(1000), false, mark("alice"));
        Envelope b = new Envelope("d1", payload("name", "Auth"), Instant.ofEpochMilli(1000), false, mark("bob"));
        assertNotEquals(Versions.versionToken(a), Versions.versionToken(b));
    }

    /**
     * The token cannot depend on map iteration order, or the same version would
     * address differently on two adapters.
     */
    @Test
    void payloadKeyOrderDoesNotChangeTheToken() {
        Map<String, Object> forwards = new LinkedHashMap<>();
        forwards.put("a", 1);
        forwards.put("b", 2);

        Map<String, Object> backwards = new LinkedHashMap<>();
        backwards.put("b", 2);
        backwards.put("a", 1);

        assertEquals(
                Versions.versionToken(env("d1", 1000, new Stash(forwards))),
                Versions.versionToken(env("d1", 1000, new Stash(backwards))));
    }

    /**
     * The same version written with a nested object's keys in a different order
     * must address the same, or a version URL stops resolving after a round trip
     * through anything that reorders a map. Sorting only the top level would pass
     * every other test here and fail this one.
     */
    @Test
    void nestedKeyOrderDoesNotChangeTheToken() {
        Map<String, Object> forwards = new LinkedHashMap<>();
        forwards.put("x", 1);
        forwards.put("y", 2);

        Map<String, Object> backwards = new LinkedHashMap<>();
        backwards.put("y", 2);
        backwards.put("x", 1);

        assertEquals(
                Versions.versionToken(env("d1", 1000, payload("meta", forwards))),
                Versions.versionToken(env("d1", 1000, payload("meta", backwards))));
    }

    /** Order inside an array is data, not incidental: [1,2] is not [2,1]. */
    @Test
    void arrayOrderDoesChangeTheToken() {
        assertNotEquals(
                Versions.versionToken(env("d1", 1000, payload("tags", List.of("x", "y")))),
                Versions.versionToken(env("d1", 1000, payload("tags", List.of("y", "x")))));
    }

    /**
     * Tokens taken from the Rust implementation and pinned here.
     *
     * <p>An address that differs between implementations is a version URL that
     * stops resolving when a deployment moves from one of them to another, and the
     * only way to notice is to compare the bytes. These five were produced by
     * {@code meshql_core::version_token} and cover every part of the derivation:
     * the flat case, a nested object written with its keys out of order beside an
     * array that keeps its own, a mark of two tokens that has to be sorted, a
     * tombstone, and an envelope with no mark at all.
     *
     * <p>What is <em>not</em> pinned is how a floating-point payload value is
     * written. Java's shortest representation and Rust's agree over the range JSON
     * payloads live in, and diverge in exponent form at the extremes; if a token
     * ever disagrees across implementations for a payload carrying a very large or
     * very small float, {@code Versions.writeNumber} is where to look.
     */
    @Test
    void tokensMatchTheOtherImplementationsByteForByte() {
        Map<String, Object> simple = new LinkedHashMap<>();
        simple.put("name", "Auth");
        simple.put("tier", "prod");
        assertEquals("178d0564f9eb77a977ea9ff0eb0836917413ba5dc2e6d5bd6b416883cdfb690b",
                token("d1", 1000, false, mark("*"), simple));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("y", 2);
        meta.put("x", 1);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("meta", meta);
        nested.put("a", 1);
        nested.put("tags", List.of("z", "a"));
        assertEquals("0a848449f0854ce7140dbcf7cdb74db6c8e5175675e5057d1ef1d30c913d6007",
                token("d1", 1000, false, mark("*"), nested));

        Map<String, Object> widget = new LinkedHashMap<>();
        widget.put("kind", "tool");
        widget.put("name", "alpha");
        assertEquals("fb3f0e8650898e0157596385e0bc93310a43d788ac130bc64fefb42d9e4fb2ef",
                token("w7", 1730000000123L, false, mark("tenant-b", "tenant-a"), widget));
        assertEquals("233cf2ad2994b27dfaf616f946efb0941e08abf4c512fb1d3cbe89058d034e05",
                token("w7", 1730000000123L, true, mark("tenant-a"), widget));

        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("kind", "tool");
        mixed.put("n", 1.5);
        mixed.put("big", 42);
        assertEquals("83e33beb1d3fd11e3d1bd002bd59976b099ac65ecd694ade0a16633204c75c82",
                token("w7", 1730000000123L, false, null, mixed));
    }

    private static String token(String id, long millis, boolean deleted, Stash auth,
                                Map<String, Object> payload) {
        return Versions.versionToken(
                new Envelope(id, new Stash(payload), Instant.ofEpochMilli(millis), deleted, auth));
    }

    @Test
    void canonicalJsonSortsEveryLevelAndLeavesArraysAlone() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("y", 2);
        nested.put("x", 1);

        Map<String, Object> top = new LinkedHashMap<>();
        top.put("meta", nested);
        top.put("a", 1);
        top.put("tags", List.of("z", "a"));

        assertEquals("{\"a\":1,\"meta\":{\"x\":1,\"y\":2},\"tags\":[\"z\",\"a\"]}",
                Versions.canonicalJson(top));
    }

    /**
     * The mark reduces to the parts every implementation hashes, sorted. The
     * default plugin wraps them in a {@code tokens} key that Rust's flat mark does
     * not have, and the token has to be the same on both.
     */
    @Test
    void markPartsAreTheSortedLeavesOfTheMark() {
        assertEquals(List.of("alice"), Versions.markParts(mark("alice")));
        assertEquals(List.of("tenant-a", "tenant-b"), Versions.markParts(mark("tenant-b", "tenant-a")));
        assertEquals(List.of(), Versions.markParts(null));
    }

    /**
     * A write and the delete that buries it land in the same millisecond routinely,
     * and a deletion is by definition later than the version it deletes. Without
     * this key the tombstone sorts by hash and lands anywhere in the history.
     */
    @Test
    void aDeletionSortsAfterTheVersionItBuries() {
        Envelope written = env("d1", 1000, payload("kind", "tool"));
        Envelope buriedBy = new Envelope("d1", written.payload(), written.createdAt(), true, written.auth());

        assertTrue(Versions.compare(written, buriedBy) < 0);
        assertTrue(Versions.compare(buriedBy, written) > 0);

        List<VersionRef> refs = Versions.listing(List.of(buriedBy, written), everyone());
        assertFalse(refs.get(0).deleted());
        assertTrue(refs.get(1).deleted());
    }

    @Test
    void orderingIsOldestFirstAndTotalWithinAMillisecond() {
        Envelope older = env("d1", 1000, payload("n", 1));
        Envelope a = env("d1", 2000, payload("n", 2));
        Envelope b = env("d1", 2000, payload("n", 3));

        assertTrue(Versions.compare(older, a) < 0);
        assertNotEquals(0, Versions.compare(a, b));
        assertEquals(Versions.compare(a, b), Versions.compare(a, b));
        assertEquals(Versions.compare(a, b), -Versions.compare(b, a));
    }

    @Test
    void aListingIsOldestFirst() {
        Envelope newer = env("d1", 2000, payload("n", 2));
        Envelope older = env("d1", 1000, payload("n", 1));

        List<VersionRef> refs = Versions.listing(List.of(newer, older), everyone());

        assertEquals(2, refs.size());
        assertEquals(Instant.ofEpochMilli(1000), refs.get(0).createdAt());
        assertEquals(Instant.ofEpochMilli(2000), refs.get(1).createdAt());
    }

    @Test
    void anUnreadableVersionCarriesWhenButNotWhat() {
        Envelope secret = env("d1", 1000, payload("secret", true));

        List<VersionRef> refs = Versions.listing(List.of(secret), nobody());

        assertEquals(1, refs.size());
        assertFalse(refs.get(0).authorized());
        assertNull(refs.get(0).token());
        assertEquals(secret.createdAt(), refs.get(0).createdAt());
    }

    @Test
    void anUnknownTokenResolvesToNothing() {
        Envelope only = env("d1", 1000, payload("n", 1));
        assertTrue(Versions.resolve(List.of(only), "nosuchversion", everyone()).isEmpty());
    }

    @Test
    void aVersionTheCallerCannotReadIsRefusedRatherThanHidden() {
        Envelope only = env("d1", 1000, payload("n", 1));
        String token = Versions.versionToken(only);

        List<Envelope> versions = new ArrayList<>(List.of(only));
        try {
            Versions.resolve(versions, token, nobody());
            throw new AssertionError("A version the caller cannot read must be refused, not reported absent");
        } catch (NotAuthorizedException expected) {
            // The listing already said this version exists.
        }
    }

    private static AuthSession everyone() {
        return new AuthSession() {
            @Override
            public Envelope stamp(Envelope envelope) {
                return envelope;
            }

            @Override
            public boolean isAuthorized(Operation operation, Envelope envelope) {
                return true;
            }
        };
    }

    private static AuthSession nobody() {
        return new AuthSession() {
            @Override
            public Envelope stamp(Envelope envelope) {
                return envelope;
            }

            @Override
            public boolean isAuthorized(Operation operation, Envelope envelope) {
                return false;
            }
        };
    }
}

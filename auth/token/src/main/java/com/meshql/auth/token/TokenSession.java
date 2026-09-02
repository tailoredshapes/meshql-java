package com.meshql.auth.token;

import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.Operation;

import java.util.List;

/**
 * The default meshql authorization rule, and the only place it exists.
 *
 * <p>Three behaviours used to live in framework code, where any adapter could
 * re-derive them and eleven of them did. They live here now:
 *
 * <ul>
 *   <li>The wildcard {@code "*"} entitles its holder to every record, and marks a
 *       record readable by every caller.</li>
 *   <li>A record stamped with no tokens is public. Public-ness is a property of
 *       the record, never of the caller: a caller presenting no tokens is
 *       anonymous, and reaches public records and nothing else. Reading it the
 *       other way - "no credentials means an internal call" - is what let an
 *       anonymous HTTP request read every record in every Java backend.</li>
 *   <li>A write stamps the record with the caller's own tokens.</li>
 * </ul>
 */
public class TokenSession implements AuthSession {
    /**
     * The token that entitles its holder to every record, and the token that makes
     * a record readable by every caller.
     */
    public static final String WILDCARD = "*";

    private final List<String> callerTokens;

    public TokenSession(List<String> callerTokens) {
        this.callerTokens = callerTokens == null ? List.of() : List.copyOf(callerTokens);
    }

    /** The session a caller outside a request uses: entitled to everything, explicitly. */
    public static TokenSession system() {
        return new TokenSession(List.of(WILDCARD));
    }

    /** The tokens this session's caller holds. Exposed for certification fixtures. */
    public List<String> tokens() {
        return callerTokens;
    }

    @Override
    public Envelope stamp(Envelope envelope) {
        return new Envelope(
                envelope.id(),
                envelope.payload(),
                envelope.createdAt(),
                envelope.deleted(),
                TokenMark.of(callerTokens));
    }

    @Override
    public boolean isAuthorized(Operation operation, Envelope envelope) {
        if (operation == Operation.CREATE) {
            // Anyone who reached the write path may write; what they may write is
            // decided by what stamp() puts on the record.
            return true;
        }
        List<String> marked = TokenMark.tokensOf(envelope);
        if (marked.isEmpty()) {
            return true;
        }
        if (callerTokens.contains(WILDCARD) || marked.contains(WILDCARD)) {
            return true;
        }
        return marked.stream().anyMatch(callerTokens::contains);
    }
}

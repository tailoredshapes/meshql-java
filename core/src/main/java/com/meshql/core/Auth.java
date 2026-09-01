package com.meshql.core;

import com.tailoredshapes.stash.Stash;
import java.util.List;

public interface Auth {
    /**
     * The token that entitles its holder to every record, and the token that makes
     * a record readable by every caller.
     */
    String WILDCARD_TOKEN = "*";

    List<String> getAuthToken(Stash context);
    boolean isAuthorized(List<String> credentials, Envelope data);

    /**
     * The record-level visibility rule every surface applies.
     *
     * <p>Public-ness is a property of the envelope, not of the caller. An envelope
     * carrying no authorized tokens is public and stays readable by anyone. A caller
     * sees everything only by holding {@link #WILDCARD_TOKEN} explicitly. A caller
     * holding no tokens at all falls through to the intersection, which is empty, so
     * an anonymous read reaches public records and nothing else.
     *
     * <p>Every backend used to grant a caller with no tokens a bypass, on the reading
     * that a credential-less call is an internal or system read. That conflates two
     * different callers into one representation: a system read presents no tokens, and
     * so does an anonymous HTTP request. An internal reader that needs to see
     * everything asks for it with {@link #WILDCARD_TOKEN}.
     *
     * @param authorizedTokens the tokens the envelope carries, or null
     * @param callerTokens     the tokens the caller presents, or null
     * @return true when the caller is entitled to see the envelope
     */
    static boolean tokensVisibleTo(List<String> authorizedTokens, List<String> callerTokens) {
        if (authorizedTokens == null || authorizedTokens.isEmpty()) {
            return true;
        }
        List<String> caller = callerTokens != null ? callerTokens : List.of();
        if (caller.contains(WILDCARD_TOKEN) || authorizedTokens.contains(WILDCARD_TOKEN)) {
            return true;
        }
        return authorizedTokens.stream().anyMatch(caller::contains);
    }

    /**
     * Whether a caller holding these tokens is entitled to every record.
     *
     * <p>Storage backends that filter inside a query use this to decide whether to
     * append a visibility predicate at all.
     */
    static boolean seesEverything(List<String> callerTokens) {
        return callerTokens != null && callerTokens.contains(WILDCARD_TOKEN);
    }
}

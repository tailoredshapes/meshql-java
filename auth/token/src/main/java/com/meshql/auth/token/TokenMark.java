package com.meshql.auth.token;

import com.meshql.core.Envelope;
import com.tailoredshapes.stash.Stash;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape the token plugins stamp on an envelope: {@code {"tokens": [...]}}.
 *
 * <p>This is the token plugin's private business. Storage persists the mark
 * verbatim and never looks inside it; nothing in the framework reads it. The two
 * places outside this package that use it are the certification suites for the
 * token plugin itself, which assert what was stamped, and that is the only
 * reason it is public.
 */
public final class TokenMark {
    /** The key the mark stores its tokens under. */
    public static final String TOKENS = "tokens";

    private TokenMark() {
    }

    /** Build a mark carrying these tokens. */
    public static Stash of(List<String> tokens) {
        Stash mark = new Stash();
        mark.put(TOKENS, tokens == null ? List.of() : List.copyOf(tokens));
        return mark;
    }

    /** The tokens an envelope was stamped with, or empty if it carries no mark. */
    @SuppressWarnings("unchecked")
    public static List<String> tokensOf(Envelope envelope) {
        if (envelope == null || envelope.auth() == null) {
            return List.of();
        }
        Object tokens = envelope.auth().get(TOKENS);
        if (!(tokens instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object token : iterable) {
            if (token != null) {
                result.add(token.toString());
            }
        }
        return result;
    }
}

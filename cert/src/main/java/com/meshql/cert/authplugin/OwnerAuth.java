package com.meshql.cert.authplugin;

import com.meshql.auth.jwt.JWTSubAuthorizer;
import com.meshql.core.Auth;
import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.Operation;
import com.tailoredshapes.stash.Stash;

import java.util.List;

/**
 * A plugin that authorizes on a payload field and holds no tokens at all.
 *
 * <p>This is the join case in miniature, and it is the scenario that would have
 * caught every defect the spec lists. It stamps no mark: {@link Envelope#auth()}
 * stays null, so a storage adapter that assumed the mark was a token list, or
 * that a record with no tokens is public, gets the wrong answer and says so.
 *
 * <p>What it does stamp is the payload. {@code stamp} is where a plugin decides
 * what is persisted, and what this one needs persisted is who wrote the record,
 * so it writes the caller into an {@code owner} field. On read it compares that
 * field to the caller and answers.
 */
public class OwnerAuth implements Auth {
    /** The payload field this plugin authorizes on. */
    public static final String OWNER = "owner";

    private final JWTSubAuthorizer identity = new JWTSubAuthorizer();

    @Override
    public AuthSession authenticate(Stash requestContext) {
        List<String> subjects = identity.resolve(requestContext);
        return new OwnerSession(subjects.isEmpty() ? null : subjects.get(0));
    }

    /** The harness's window onto storage: entitled to everything, and says so. */
    @Override
    public AuthSession systemSession() {
        return new OwnerSession(null) {
            @Override
            public boolean isAuthorized(Operation operation, Envelope envelope) {
                return true;
            }
        };
    }

    private static class OwnerSession implements AuthSession {
        private final String caller;

        OwnerSession(String caller) {
            this.caller = caller;
        }

        /**
         * Record the writer in the payload, and stamp no mark.
         *
         * <p>Nothing goes in {@code auth}, which is the point: a backend that needs
         * a token list there to work at all fails this scenario.
         */
        @Override
        public Envelope stamp(Envelope envelope) {
            if (caller == null || envelope.payload() == null) {
                return envelope;
            }
            Stash payload = new Stash(envelope.payload());
            payload.put(OWNER, caller);
            return new Envelope(
                    envelope.id(),
                    payload,
                    envelope.createdAt(),
                    envelope.deleted(),
                    null);
        }

        @Override
        public boolean isAuthorized(Operation operation, Envelope envelope) {
            if (operation == Operation.CREATE) {
                return true;
            }
            if (caller == null || envelope == null || envelope.payload() == null) {
                return false;
            }
            return caller.equals(envelope.payload().get(OWNER));
        }
    }
}

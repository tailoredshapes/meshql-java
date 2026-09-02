package com.meshql.cert.authplugin;

import com.meshql.auth.jwt.JWTSubAuthorizer;
import com.meshql.auth.token.TokenSession;
import com.meshql.core.Auth;
import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.Operation;
import com.tailoredshapes.stash.Stash;

/**
 * A plugin that stamps records the ordinary way and then refuses to let anyone
 * read them.
 *
 * <p>It exists to catch the one defect no other suite can see. Against the
 * default token plugin, a storage adapter that reimplements the token rule
 * correctly and one that delegates return exactly the same rows, so
 * {@code authz.feature} cannot tell them apart. Against this plugin it can: an
 * adapter that answers for itself keeps returning records, and an adapter that
 * asks returns none.
 *
 * <p>Writes are unaffected, and the stamp is the token plugin's, so anything
 * that reads the stored mark still sees the shape it expects. Only reads are
 * refused - along with removes, because a caller who cannot see a record has no
 * business deleting it.
 */
public class RefuseReadsAuth implements Auth {
    private final JWTSubAuthorizer identity = new JWTSubAuthorizer();

    @Override
    public AuthSession authenticate(Stash requestContext) {
        return new RefusingSession(new TokenSession(identity.resolve(requestContext)));
    }

    /**
     * The certification harness's own window onto storage, which has to work:
     * otherwise the suite could not resolve a widget's id and every scenario would
     * fail for the wrong reason.
     */
    @Override
    public AuthSession systemSession() {
        return TokenSession.system();
    }

    private record RefusingSession(TokenSession stamping) implements AuthSession {
        @Override
        public Envelope stamp(Envelope envelope) {
            return stamping.stamp(envelope);
        }

        @Override
        public boolean isAuthorized(Operation operation, Envelope envelope) {
            return operation == Operation.CREATE;
        }
    }
}

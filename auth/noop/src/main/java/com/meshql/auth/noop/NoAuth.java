package com.meshql.auth.noop;

import com.meshql.core.Auth;
import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.Operation;
import com.tailoredshapes.stash.Stash;

/**
 * The plugin for a deployment inside a trust boundary: everything is authorized.
 *
 * <p>It says {@code true}, which is what a plugin named No Auth should say. It
 * used to say {@code true} only because it was constructed that way, and stamped
 * a token called "Token" onto everything it wrote - a mark nobody held and
 * nothing checked.
 */
public class NoAuth implements Auth {

    /** Authorizes everything and stamps nothing. */
    public static final AuthSession SESSION = new AuthSession() {
        @Override
        public Envelope stamp(Envelope envelope) {
            return envelope;
        }

        @Override
        public boolean isAuthorized(Operation operation, Envelope envelope) {
            return true;
        }
    };

    @Override
    public AuthSession authenticate(Stash requestContext) {
        return SESSION;
    }

    @Override
    public AuthSession systemSession() {
        return SESSION;
    }
}

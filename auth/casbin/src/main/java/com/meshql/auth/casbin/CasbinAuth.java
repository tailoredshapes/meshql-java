package com.meshql.auth.casbin;

import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.tailoredshapes.stash.Stash;
import org.casbin.jcasbin.main.Enforcer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.tailoredshapes.underbar.ocho.UnderBar.list;

/**
 * Casbin-based authorizer that wraps another Auth implementation (typically JWT)
 * to provide role-based access control.
 *
 * This implementation:
 * 1. Delegates to the wrapped Auth to get the user identity (e.g., JWT sub claim)
 * 2. Uses Casbin's Enforcer to look up roles for that user
 * 3. Returns the roles as credentials for authorization checks
 *
 * The authorization check compares the user's roles against the document's
 * authorized_tokens list.
 *
 * <h2>Why {@code enforcer.enforce(...)} is never called</h2>
 *
 * It is not an oversight. MeshQL resolves identity to capabilities <em>once</em>,
 * at the boundary, and propagates the result; every check below that point is a
 * local intersection of that token set against a record's authorized_tokens.
 * Casbin's job here is subject -&gt; role resolution and nothing else.
 *
 * <p>Adding a deeper {@code enforce} is not defence in depth. It recomputes a
 * decision already made, at every layer that repeats it, and against a remote
 * policy engine it is a network call per layer - per record, on a vector read.
 * The meshlettes get chatty and the cause is not visible from the call site.
 *
 * <p>Because the absence looks like a bug to every scanner and reviewer, it is
 * pinned by a test rather than by a comment:
 * {@code com.meshql.api.graphql.PolicyEvaluationCountTest#readPathMakesNoPolicyEvaluations}
 * wraps the Enforcer in a counting decorator and asserts a read through the
 * graphlette performs zero policy evaluations. Add an {@code enforce} call here
 * and that test goes red the same day, saying why.
 */
public class CasbinAuth implements Auth {
    private static final Logger logger = LoggerFactory.getLogger(CasbinAuth.class);

    private final Enforcer enforcer;
    private final Auth wrappedAuth;

    /**
     * Create a CasbinAuth with an existing Enforcer and wrapped Auth.
     *
     * @param enforcer The Casbin Enforcer configured with model and policy
     * @param wrappedAuth The underlying Auth (e.g., JWTSubAuthorizer) to get user identity
     */
    public CasbinAuth(Enforcer enforcer, Auth wrappedAuth) {
        this.enforcer = enforcer;
        this.wrappedAuth = wrappedAuth;
    }

    /**
     * Factory method to create CasbinAuth from model and policy file paths.
     *
     * @param modelPath Path to the Casbin model file (e.g., "model.conf")
     * @param policyPath Path to the Casbin policy file (e.g., "policy.csv")
     * @param wrappedAuth The underlying Auth to get user identity
     * @return A configured CasbinAuth instance
     */
    public static CasbinAuth create(String modelPath, String policyPath, Auth wrappedAuth) {
        Enforcer enforcer = new Enforcer(modelPath, policyPath);
        return new CasbinAuth(enforcer, wrappedAuth);
    }

    /**
     * Get the roles for the authenticated user.
     *
     * 1. Delegates to wrapped auth to get user identity (e.g., "user123" from JWT sub)
     * 2. Looks up roles for that user in Casbin (e.g., ["admin", "editor"])
     * 3. Returns the roles as the auth tokens
     *
     * @param context The request context containing auth headers
     * @return List of roles for the user, or empty list if no user or roles found
     */
    @Override
    public List<String> getAuthToken(Stash context) {
        try {
            // Get the user identity from the wrapped auth (e.g., JWT sub claim)
            List<String> userIds = wrappedAuth.getAuthToken(context);

            if (userIds == null || userIds.isEmpty()) {
                return list();
            }

            // Get the first user ID (typically just one from JWT sub)
            String userId = userIds.get(0);

            // Look up roles for this user in Casbin
            List<String> roles = enforcer.getRolesForUser(userId);

            if (roles == null || roles.isEmpty()) {
                logger.debug("No roles found for user: {}", userId);
                return list();
            }

            return roles;
        } catch (Exception e) {
            logger.error("Failed to get roles from Casbin", e);
            return list();
        }
    }

    /**
     * Check if the credentials (roles) authorize access to the data.
     *
     * <p>Delegates to {@link Auth#tokensVisibleTo}, the one rule every read surface
     * shares, so the Searcher and the Repository cannot drift apart.
     *
     * @param credentials List of roles from getAuthToken
     * @param data The envelope containing authorized_tokens
     * @return true if any role matches authorized_tokens, or if authorized_tokens is empty
     */
    @Override
    public boolean isAuthorized(List<String> credentials, Envelope data) {
        return Auth.tokensVisibleTo(data.authorizedTokens(), credentials);
    }

    /**
     * Get the underlying Casbin Enforcer for advanced use cases.
     *
     * @return The Casbin Enforcer
     */
    public Enforcer getEnforcer() {
        return enforcer;
    }
}

package com.meshql.auth.casbin;

import com.meshql.auth.token.Identity;
import com.meshql.auth.token.TokenSession;
import com.meshql.core.Auth;
import com.meshql.core.AuthSession;
import com.tailoredshapes.stash.Stash;
import org.casbin.jcasbin.main.Enforcer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.tailoredshapes.underbar.ocho.UnderBar.list;

/**
 * Token plugin that resolves a caller's identity to roles, and uses the roles as
 * the caller's tokens.
 *
 * <p>It wraps another {@link Auth} - typically {@code JWTSubAuthorizer} - to get
 * the identity, then asks Casbin which roles that subject holds. The rule it
 * authorizes by is {@link TokenSession}, shared with every other token plugin.
 *
 * <h2>Why {@code enforcer.enforce(...)} is never called</h2>
 *
 * It is not an oversight. MeshQL resolves identity to capabilities <em>once</em>,
 * at the boundary, and propagates the resulting session; every check below that
 * point is a local question asked of that session. Casbin's job here is
 * subject -&gt; role resolution and nothing else.
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
    private final Identity identity;

    /**
     * Create a CasbinAuth with an existing Enforcer and wrapped Auth.
     *
     * @param enforcer The Casbin Enforcer configured with model and policy
     * @param identity Resolves the request to a subject (e.g., JWTSubAuthorizer)
     */
    public CasbinAuth(Enforcer enforcer, Identity identity) {
        this.enforcer = enforcer;
        this.identity = identity;
    }

    /**
     * Factory method to create CasbinAuth from model and policy file paths.
     */
    public static CasbinAuth create(String modelPath, String policyPath, Identity identity) {
        Enforcer enforcer = new Enforcer(modelPath, policyPath);
        return new CasbinAuth(enforcer, identity);
    }

    @Override
    public AuthSession authenticate(Stash requestContext) {
        return new TokenSession(roles(requestContext));
    }

    @Override
    public AuthSession systemSession() {
        return TokenSession.system();
    }

    /**
     * The roles the request's caller holds, which are the tokens it presents.
     *
     * <p>An unknown subject, or none at all, holds no roles and so reaches public
     * records and nothing else.
     */
    List<String> roles(Stash requestContext) {
        try {
            List<String> userIds = identity.resolve(requestContext);

            if (userIds == null || userIds.isEmpty()) {
                return list();
            }

            String userId = userIds.get(0);
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
     * Get the underlying Casbin Enforcer for advanced use cases.
     */
    public Enforcer getEnforcer() {
        return enforcer;
    }
}

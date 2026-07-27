package com.meshql.cert;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * The credential fixture for the authorization certification.
 *
 * <p>The certification deliberately avoids "star" credentials — a caller entitled
 * to everything makes a working authorization filter and a completely inert one
 * indistinguishable. Instead it defines two ordinary, mutually exclusive callers
 * ("alice" holding only {@code tenant-a}, "bob" holding only {@code tenant-b})
 * plus one caller holding every token ("root"), so that both directions can be
 * asserted: the filter must exclude, and it must not over-exclude.
 *
 * <p>Credentials are supplied the way a real deployment supplies them: an
 * {@code Authorization: Bearer <jwt>} header. The JWT is unsigned because
 * {@code JWTSubAuthorizer} is decode-only by design (the edge authenticates,
 * the framework authorizes).
 */
public final class AuthzCredentials {

    /** Caller A. Casbin resolves this subject to the single role {@code tenant-a}. */
    public static final String USER_A = "alice";
    /** Caller B. Casbin resolves this subject to the single role {@code tenant-b}. */
    public static final String USER_B = "bob";
    /** The "sees everything" caller. Casbin resolves this subject to every role. */
    public static final String USER_STAR = "root";

    public static final String TOKEN_A = "tenant-a";
    public static final String TOKEN_B = "tenant-b";

    private AuthzCredentials() {
    }

    /**
     * A Casbin RBAC model that maps subjects to roles via {@code g}.
     *
     * <p>Note that MeshQL never calls {@code enforcer.enforce(...)}: Casbin's job here
     * is subject -> role resolution only. Record-level authorization is the token
     * intersection performed in {@code Auth.isAuthorized}. The matcher below therefore
     * exists to make the model file well-formed, not because it is consulted.
     */
    public static final String MODEL = """
            [request_definition]
            r = sub, obj, act

            [policy_definition]
            p = sub, obj, act

            [role_definition]
            g = _, _

            [policy_effect]
            e = some(where (p.eft == allow))

            [matchers]
            m = g(r.sub, p.sub) && r.obj == p.obj && r.act == p.act
            """;

    /**
     * alice -> [tenant-a], bob -> [tenant-b], root -> [tenant-a, tenant-b].
     */
    public static final String POLICY = """
            p, tenant-a, data, read
            p, tenant-b, data, read
            g, alice, tenant-a
            g, bob, tenant-b
            g, root, tenant-a
            g, root, tenant-b
            """;

    /**
     * Write the Casbin model and policy to temp files and return them in the
     * order {@code Config.casbinParams} expects: [modelPath, policyPath].
     */
    public static java.util.List<String> writeCasbinParams() throws java.io.IOException {
        Path model = Files.createTempFile("meshql_authz_model", ".conf");
        Path policy = Files.createTempFile("meshql_authz_policy", ".csv");
        model.toFile().deleteOnExit();
        policy.toFile().deleteOnExit();
        Files.writeString(model, MODEL);
        Files.writeString(policy, POLICY);
        return java.util.List.of(model.toString(), policy.toString());
    }

    /**
     * Build an {@code Authorization} header value for the given subject.
     *
     * <p>The JWT is not signed. {@code JWTSubAuthorizer} decodes rather than verifies,
     * on the documented assumption that an upstream gateway has already authenticated
     * the caller. The certification is about authorization, not authentication.
     */
    public static String bearer(String subject) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
        return "Bearer " + header + "." + payload + ".unsigned";
    }

    /** Map a caller label used in the feature file to its bearer header, or null for anonymous. */
    public static String headerFor(String caller) {
        return switch (caller) {
            case "A", "alice" -> bearer(USER_A);
            case "B", "bob" -> bearer(USER_B);
            case "star", "root" -> bearer(USER_STAR);
            case "anonymous", "nobody", "none" -> null;
            default -> throw new IllegalArgumentException("Unknown caller: " + caller);
        };
    }

    /** The tokens Auth is expected to resolve for a caller. */
    public static java.util.List<String> expectedTokensFor(String caller) {
        return switch (caller) {
            case "A", "alice" -> java.util.List.of(TOKEN_A);
            case "B", "bob" -> java.util.List.of(TOKEN_B);
            case "star", "root" -> java.util.List.of(TOKEN_A, TOKEN_B);
            case "anonymous", "nobody", "none" -> java.util.List.of();
            default -> throw new IllegalArgumentException("Unknown caller: " + caller);
        };
    }
}

package com.meshql.auth.jwt;

import com.meshql.auth.token.Identity;
import com.meshql.auth.token.TokenSession;
import com.meshql.core.Auth;
import com.meshql.core.AuthSession;
import com.tailoredshapes.stash.Stash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.tailoredshapes.underbar.ocho.UnderBar.list;

/**
 * Token plugin that takes the caller's single token from a JWT's {@code sub} claim.
 *
 * <p>IMPORTANT: this implementation does NOT verify the JWT signature. It is
 * designed for deployments where an upstream gateway (API gateway, Istio, Kong)
 * has already authenticated the caller. The edge authenticates; the plugin
 * authorizes.
 *
 * <p>The rule it authorizes by lives in {@link TokenSession}, shared with every
 * other token plugin, so there is one implementation of it rather than one per
 * caller.
 */
public class JWTSubAuthorizer implements Auth, Identity {
    private static final Logger logger = LoggerFactory.getLogger(JWTSubAuthorizer.class);

    @Override
    public AuthSession authenticate(Stash requestContext) {
        return new TokenSession(resolve(requestContext));
    }

    @Override
    public AuthSession systemSession() {
        return TokenSession.system();
    }

    /**
     * The tokens this request's caller holds: the JWT subject, or none.
     *
     * <p>No credentials resolves to no tokens, which reaches public records and
     * nothing else. It is not a bypass, and a reader that needs to see everything
     * asks for {@link #systemSession()}.
     */
    @Override
    public List<String> resolve(Stash context) {
        try {
            if (context == null) {
                return list();
            }
            Object headersObj = context.get("headers");
            if (headersObj == null) {
                return list();
            }

            String authHeader = null;
            if (headersObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> headers = (Map<String, Object>) headersObj;
                Object authObj = headers.get("authorization");
                if (authObj != null) {
                    authHeader = authObj.toString();
                }
            } else if (headersObj instanceof Stash) {
                Stash headers = (Stash) headersObj;
                Object authObj = headers.get("authorization");
                if (authObj != null) {
                    authHeader = authObj.toString();
                }
            }

            if (authHeader == null || authHeader.isEmpty()) {
                return list();
            }

            if (!authHeader.startsWith("Bearer ")) {
                logger.error("Missing Bearer Token");
                return list();
            }

            String token = authHeader.substring(7);
            String subject = extractSubject(token);

            if (subject == null) {
                return list();
            }

            return list(subject);
        } catch (Exception e) {
            logger.error("Failed to extract auth token", e);
            return list();
        }
    }

    /**
     * Extract the 'sub' claim from a JWT token without verifying the signature.
     *
     * JWT structure: header.payload.signature
     * We decode the payload (middle part) and extract the 'sub' field.
     */
    private String extractSubject(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                logger.error("Invalid JWT format - expected 3 parts, got {}", parts.length);
                return null;
            }

            // Decode the payload (second part)
            String payload = parts[1];
            // JWT uses URL-safe base64, handle padding
            String decoded = new String(Base64.getUrlDecoder().decode(payload));

            // Parse as Stash (JSON)
            Stash claims = Stash.parseJSON(decoded);
            Object subObj = claims.get("sub");
            String subject = subObj != null ? subObj.toString() : null;

            if (subject == null) {
                logger.warn("JWT token does not contain 'sub' claim");
            }

            return subject;
        } catch (Exception e) {
            logger.error("Failed to decode JWT token", e);
            return null;
        }
    }
}

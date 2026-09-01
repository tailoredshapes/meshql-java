package com.meshql.auth.jwt;

import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.tailoredshapes.stash.Stash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.tailoredshapes.underbar.ocho.UnderBar.list;

/**
 * JWT-based authorizer that extracts the 'sub' claim from a JWT token.
 *
 * IMPORTANT: This implementation does NOT verify the JWT signature.
 * It is designed for enterprise environments where JWT validation
 * is handled by upstream services (API gateway, Istio, Kong, etc.)
 * before the request reaches this application.
 *
 * The token is assumed to be pre-validated; we only decode and extract claims.
 */
public class JWTSubAuthorizer implements Auth {
    private static final Logger logger = LoggerFactory.getLogger(JWTSubAuthorizer.class);

    @Override
    public List<String> getAuthToken(Stash context) {
        try {
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
     * Check whether the credentials authorize access to the data.
     *
     * <p>Delegates to {@link Auth#tokensVisibleTo}, the one rule every read surface
     * shares, so the Searcher and the Repository cannot drift apart.
     */
    @Override
    public boolean isAuthorized(List<String> credentials, Envelope data) {
        return Auth.tokensVisibleTo(data.authorizedTokens(), credentials);
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

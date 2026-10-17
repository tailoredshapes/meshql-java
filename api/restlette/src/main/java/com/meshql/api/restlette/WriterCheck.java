package com.meshql.api.restlette;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Who may write to a restlette that names its writers.
 *
 * <p>A credential is the {@code sub} claim of the {@code Authorization: Bearer}
 * JWT, or the bearer token itself when it is not a JWT. The signature is not
 * verified, exactly as in {@code JWTSubAuthorizer}: the edge authenticates, this
 * decides. It is independent of the auth plugin on purpose, so it works under
 * {@code NoAuth} and under any other: the plugin answers "may this caller see or
 * stamp this record", this answers "is this caller the one party that writes here".
 *
 * <p>An empty list means the restlette is open to any writer.
 */
final class WriterCheck {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> writers;

    WriterCheck(List<String> writers) {
        this.writers = writers == null ? List.of() : List.copyOf(writers);
    }

    boolean open() {
        return writers.isEmpty();
    }

    boolean permits(HttpServletRequest request) {
        if (open()) {
            return true;
        }
        return credential(request.getHeader("Authorization")).filter(writers::contains).isPresent();
    }

    static Optional<String> credential(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length >= 2) {
            try {
                JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
                JsonNode sub = claims.get("sub");
                if (sub != null && sub.isTextual()) {
                    return Optional.of(sub.asText());
                }
            } catch (Exception notAJwt) {
                // fall through: the bearer token is the credential
            }
        }
        return Optional.of(token);
    }
}

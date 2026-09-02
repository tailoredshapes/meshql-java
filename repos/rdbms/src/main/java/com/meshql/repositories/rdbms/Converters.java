package com.meshql.repositories.rdbms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshql.core.Envelope;
import com.tailoredshapes.stash.Stash;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Utility class for converting between JDBC ResultSet objects and domain
 * objects.
 */
public interface Converters {
    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Converts a ResultSet row to an Envelope object.
     *
     * <p>The {@code auth} column holds the auth plugin's mark, written verbatim
     * and read back verbatim. Nothing in this package interprets it.
     *
     * @param rs ResultSet positioned at the row to convert
     * @return Envelope object
     * @throws SQLException if a database access error occurs
     */
    static Envelope resultSetToEnvelope(ResultSet rs) throws SQLException {
        if (rs == null) {
            return null;
        }

        String id = rs.getString("id");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        boolean deleted = rs.getBoolean("deleted");

        Stash payload = Stash.parseJSON(rs.getString("payload"));

        return new Envelope(id, payload, createdAt, deleted, parseAuth(rs.getString("auth")));
    }

    /** Read the stored mark back into the shape the plugin stamped. */
    static Stash parseAuth(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return Stash.parseJSON(json);
    }

    /** Render the plugin's mark for storage, verbatim. */
    static String writeAuth(Stash auth) {
        return auth == null ? null : auth.toJSONString();
    }

    /**
     * Converts an Instant to a SQL timestamp.
     *
     * @param instant Instant object
     * @return SQL timestamp
     */
    static Timestamp instantToTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
}

INSERT INTO {{tableName}} (id, payload, auth, created_at, updated_at, deleted)
VALUES (?, ?::jsonb, ?::jsonb, NOW(), NOW(), TRUE)
RETURNING *;

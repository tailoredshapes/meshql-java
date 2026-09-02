SELECT DISTINCT ON (id) * FROM {{tableName}}
WHERE deleted IS FALSE
ORDER BY id, created_at DESC;

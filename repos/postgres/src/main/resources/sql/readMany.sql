SELECT DISTINCT ON (id) * FROM {{tableName}}
WHERE id = ANY(string_to_array(?, ',')) AND deleted IS FALSE
ORDER BY id, created_at DESC;

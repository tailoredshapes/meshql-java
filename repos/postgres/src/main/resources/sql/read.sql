SELECT e.* FROM {{tableName}} e
WHERE e.id = ? AND e.created_at <= ?
ORDER BY e.created_at DESC LIMIT 1;

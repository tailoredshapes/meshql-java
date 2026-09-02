SELECT * FROM (
  SELECT DISTINCT ON (id) * FROM {{tableName}}
  ORDER BY id, created_at DESC
) latest
WHERE latest.deleted IS FALSE;

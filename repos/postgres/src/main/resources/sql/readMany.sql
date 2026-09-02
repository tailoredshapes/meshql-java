SELECT * FROM (
  SELECT DISTINCT ON (id) * FROM {{tableName}}
  WHERE id = ANY(string_to_array(?, ','))
  ORDER BY id, created_at DESC
) latest
WHERE latest.deleted IS FALSE;

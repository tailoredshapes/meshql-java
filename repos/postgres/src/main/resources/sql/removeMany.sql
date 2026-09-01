UPDATE {{tableName}} SET deleted = TRUE WHERE id = ANY(string_to_array(?, ','))
{{#if hasTokens}}AND (NOT EXISTS (SELECT 1 FROM {{tableName}}_authtokens
WHERE envelope_id = id AND envelope_created_at = created_at)
OR EXISTS (SELECT 1 FROM {{tableName}}_authtokens
WHERE envelope_id = id AND envelope_created_at = created_at
AND token IN ({{#each tokens}}{{#unless @first}},{{/unless}}?{{/each}}))){{/if}}
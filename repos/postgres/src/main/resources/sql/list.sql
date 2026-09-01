WITH latest_versions AS (
SELECT DISTINCT ON (id) * FROM {{tableName}}
WHERE deleted IS FALSE
{{#if hasTokens}}AND (NOT EXISTS (SELECT 1 FROM {{tableName}}_authtokens
WHERE envelope_id = id AND envelope_created_at = created_at)
OR EXISTS (SELECT 1 FROM {{tableName}}_authtokens
WHERE envelope_id = id AND envelope_created_at = created_at
AND token IN ({{#each tokens}}{{#unless @first}},{{/unless}}?{{/each}}))){{/if}}
ORDER BY id, created_at DESC
)
SELECT lv.*,
ARRAY(SELECT token FROM {{tableName}}_authtokens
WHERE envelope_id = lv.id AND envelope_created_at = lv.created_at
ORDER BY token_order) AS authorized_tokens
FROM latest_versions lv;
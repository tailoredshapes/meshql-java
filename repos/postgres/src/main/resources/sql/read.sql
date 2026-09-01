SELECT e.*,
ARRAY(SELECT token FROM {{tableName}}_authtokens
WHERE envelope_id = e.id AND envelope_created_at = e.created_at
ORDER BY token_order) AS authorized_tokens
FROM {{tableName}} e
WHERE e.id = ? AND e.deleted IS FALSE AND e.created_at <= ? {{#if hasTokens}}AND (NOT EXISTS (SELECT 1 FROM
    {{tableName}}_authtokens WHERE envelope_id=e.id AND envelope_created_at=e.created_at)
    OR EXISTS (SELECT 1 FROM
    {{tableName}}_authtokens WHERE envelope_id=e.id AND envelope_created_at=e.created_at AND token IN ({{#each
    tokens}}{{#unless @first}},{{/unless}}?{{/each}}))){{/if}} ORDER BY e.created_at DESC LIMIT 1;
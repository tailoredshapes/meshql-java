CREATE TABLE IF NOT EXISTS {{tableName}} (
pk UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
id TEXT,
payload JSONB,
auth JSONB,
created_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
updated_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
deleted BOOLEAN DEFAULT FALSE,
CONSTRAINT {{tableName}}_id_created_at_uniq UNIQUE (id, created_at)
);

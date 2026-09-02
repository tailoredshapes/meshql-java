UPDATE {{tableName}} SET deleted = TRUE WHERE id = ANY(string_to_array(?, ','));

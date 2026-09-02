package com.meshql.repos.sqlite;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.repos.certification.SearcherCertification;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Files;
import java.sql.SQLException;

import static com.tailoredshapes.underbar.ocho.Die.rethrow;

public class SQLiteSearcherCertificationTest extends SearcherCertification {
    private static final Handlebars handlebars = new Handlebars();
    private int testCounter = 0;

    @Override
    public void init() {
        try {
            File dbFile = Files.createTempFile("meshql_searcher_test_", ".db").toFile();
            dbFile.deleteOnExit();

            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

            String tableName = "test" + (++testCounter);

            SQLiteRepository sqliteRepository = new SQLiteRepository(
                    dataSource.getConnection(), tableName);
            sqliteRepository.initialize();
            this.repository = sqliteRepository;

            Auth noAuth = new NoAuth();
            this.searcher = new SQLiteSearcher(dataSource, tableName);
            this.templates = createTemplates();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SQLite searcher test", e);
        }
    }

    private static SearcherTemplates createTemplates() {
        Template findById = rethrow(() -> handlebars.compileInline("id = '{{id}}'"));
        Template findByName = rethrow(() -> handlebars.compileInline("json_extract(payload, '$.name') = '{{id}}'"));
        Template findAllByType = rethrow(() -> handlebars.compileInline("json_extract(payload, '$.type') = '{{id}}'"));
        Template findByNameAndType = rethrow(() -> handlebars.compileInline(
                "json_extract(payload, '$.name') = '{{name}}' AND json_extract(payload, '$.type') = '{{type}}'"));

        return new SearcherTemplates(findById, findByName, findAllByType, findByNameAndType);
    }
}

package com.meshql.repositories.postgres;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.repos.certification.SearcherCertification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Test class for PostgresSearcher using Testcontainers.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PostgresSearcherTest extends SearcherCertification {

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:latest")
            .withUsername("alice")
            .withPassword("face")
            .withDatabaseName("repository");

    private final List<DataSource> dataSources = new ArrayList<>();
    private int testCounter = 0;
    private final Handlebars handlebars = new Handlebars();

    @BeforeAll
    public static void startContainer() {
        postgreSQLContainer.start();
    }

    @AfterAll
    public static void stopContainer() {
        postgreSQLContainer.stop();
    }

    @Override
    public void init() {
        try {
            // Create a new data source for each test
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setServerName(postgreSQLContainer.getHost());
            dataSource.setPortNumber(postgreSQLContainer.getMappedPort(5432));
            dataSource.setDatabaseName(postgreSQLContainer.getDatabaseName());
            dataSource.setUser(postgreSQLContainer.getUsername());
            dataSource.setPassword(postgreSQLContainer.getPassword());

            dataSources.add(dataSource);

            // Create a unique table name for each test
            String tableName = "test" + (++testCounter);

            // Create and initialize the repository
            PostgresRepository postgresRepository = new PostgresRepository(dataSource, tableName);
            postgresRepository.initialize();
            repository = postgresRepository;

            // Create the searcher with NoAuth
            Auth noAuth = new NoAuth();
            searcher = new PostgresSearcher(dataSource, tableName);

            // Create the templates
            templates = createTemplates();

        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to initialize PostgreSQL searcher test", e);
        }
    }

    private SearcherTemplates createTemplates() throws IOException {
        Template findById = handlebars.compileInline("id = '{{id}}'");
        Template findByName = handlebars.compileInline("payload->>'name' = '{{id}}'");
        Template findAllByType = handlebars
                .compileInline("payload->>'type' = '{{id}}'");
        Template findByNameAndType = handlebars.compileInline(
                "payload->>'type' = '{{type}}' AND payload->>'name' = '{{name}}'");

        return new SearcherTemplates(findById, findByName, findAllByType, findByNameAndType);
    }

    @AfterAll
    public void tearDown() {
        // Close all data sources
        for (DataSource dataSource : dataSources) {
            if (dataSource instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) dataSource).close();
                } catch (Exception e) {
                    // Log but continue closing other resources
                    System.err.println("Error closing data source: " + e.getMessage());
                }
            }
        }
    }
}
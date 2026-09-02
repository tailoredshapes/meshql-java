package com.meshql.repositories.ksql;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.repos.certification.SearcherCertification;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static com.tailoredshapes.underbar.ocho.Die.rethrow;

public class KsqlSearcherCertificationTest extends SearcherCertification {
    private static final Logger logger = LoggerFactory.getLogger(KsqlSearcherCertificationTest.class);

    private static KafkaProducer<String, String> producer;
    private static KsqlHttpClient ksqlClient;

    @Override
    public void init() {
        KsqlTestEnvironment.start();

        if (producer == null) {
            producer = KsqlTestEnvironment.createProducer();
            ksqlClient = KsqlTestEnvironment.createKsqlClient();
        }

        String topic = "test_search_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        KsqlRepository ksqlRepository = new KsqlRepository(producer, ksqlClient, KsqlTestEnvironment.getBootstrapServers(), topic);
        ksqlRepository.initialize(1, 1);

        this.repository = ksqlRepository;
        this.searcher = new KsqlSearcher(ksqlClient, topic);
        this.templates = createTemplates();

        logger.info("Initialized ksqlDB searcher with topic: {}", topic);
    }

    private static SearcherTemplates createTemplates() {
        Handlebars handlebars = new Handlebars();

        Template findById = rethrow(() -> handlebars.compileInline("id = '{{id}}'"));
        Template findByName = rethrow(() -> handlebars.compileInline(
                "EXTRACTJSONFIELD(payload, '$.name') = '{{id}}'"));
        Template findAllByType = rethrow(() -> handlebars.compileInline(
                "EXTRACTJSONFIELD(payload, '$.type') = '{{id}}'"));
        Template findByNameAndType = rethrow(() -> handlebars.compileInline(
                "EXTRACTJSONFIELD(payload, '$.name') = '{{name}}' AND EXTRACTJSONFIELD(payload, '$.type') = '{{type}}'"));

        return new SearcherTemplates(findById, findByName, findAllByType, findByNameAndType);
    }

    @AfterAll
    static void tini() {
        if (producer != null) {
            producer.close();
            producer = null;
        }
        if (ksqlClient != null) {
            ksqlClient.close();
            ksqlClient = null;
        }
    }
}

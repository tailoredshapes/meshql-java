package com.meshql.repositories.ksql;

import com.meshql.auth.noop.NoAuth;
import com.meshql.repos.certification.RepositoryCertification;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class KsqlRepositoryCertificationTest extends RepositoryCertification {
    private static final Logger logger = LoggerFactory.getLogger(KsqlRepositoryCertificationTest.class);

    private static KafkaProducer<String, String> producer;
    private static KsqlHttpClient ksqlClient;

    @Override
    public void init() {
        KsqlTestEnvironment.start();

        if (producer == null) {
            producer = KsqlTestEnvironment.createProducer();
            ksqlClient = KsqlTestEnvironment.createKsqlClient();
        }

        String topic = "test_repo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        KsqlRepository ksqlRepository = new KsqlRepository(
                producer, ksqlClient, KsqlTestEnvironment.getBootstrapServers(), topic);
        ksqlRepository.initialize(1, 1);

        repository = ksqlRepository;

        logger.info("Initialized ksqlDB repository with topic: {}", topic);
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

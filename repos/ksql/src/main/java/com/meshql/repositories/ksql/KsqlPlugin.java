package com.meshql.repositories.ksql;

import com.meshql.core.*;
import com.meshql.core.config.StorageConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class KsqlPlugin implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(KsqlPlugin.class);

    private final Map<String, KafkaProducer<String, String>> producers = new HashMap<>();
    private final Map<String, KsqlHttpClient> httpClients = new HashMap<>();

    private KafkaProducer<String, String> getOrCreateProducer(KsqlConfig config) {
        return producers.computeIfAbsent(config.bootstrapServers, k -> {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.ACKS_CONFIG, config.acks);
            return new KafkaProducer<>(props);
        });
    }

    private KsqlHttpClient getOrCreateHttpClient(KsqlConfig config) {
        return httpClients.computeIfAbsent(config.ksqlDbUrl, KsqlHttpClient::new);
    }

    @Override
    public Repository createRepository(StorageConfig sc) {
        KsqlConfig config = (KsqlConfig) sc;
        KafkaProducer<String, String> producer = getOrCreateProducer(config);
        KsqlHttpClient httpClient = getOrCreateHttpClient(config);

        KsqlRepository repository = new KsqlRepository(producer, httpClient, config.bootstrapServers, config.topic);

        if (config.autoCreate) {
            repository.initialize(config.partitions, config.replicationFactor);
        }

        return repository;
    }

    @Override
    public Searcher createSearcher(StorageConfig sc) {
        KsqlConfig config = (KsqlConfig) sc;
        KsqlHttpClient httpClient = getOrCreateHttpClient(config);
        return new KsqlSearcher(httpClient, config.topic);
    }

    @Override
    public void cleanUp() {
        producers.values().forEach(producer -> {
            try {
                producer.close();
            } catch (Exception e) {
                logger.warn("Error closing Kafka producer", e);
            }
        });
        producers.clear();

        httpClients.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                logger.warn("Error closing ksqlDB HTTP client", e);
            }
        });
        httpClients.clear();
    }

    @Override
    public boolean isHealthy() {
        try {
            for (KsqlHttpClient client : httpClients.values()) {
                client.serverInfo();
            }
            return !httpClients.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}

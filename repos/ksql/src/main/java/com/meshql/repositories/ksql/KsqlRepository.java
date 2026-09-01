package com.meshql.repositories.ksql;

import com.fasterxml.uuid.Generators;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Repository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static com.meshql.repositories.ksql.Converters.*;

public class KsqlRepository implements Repository {
    private static final Logger logger = LoggerFactory.getLogger(KsqlRepository.class);

    private static final int POLL_RETRIES = 50;
    private static final long POLL_INTERVAL_MS = 100;

    private final KafkaProducer<String, String> producer;
    private final KsqlHttpClient ksqlClient;
    private final String bootstrapServers;
    private final String topic;
    private final String tableName;
    private final Auth auth;

    public KsqlRepository(KafkaProducer<String, String> producer,
                           KsqlHttpClient ksqlClient,
                           String bootstrapServers,
                           String topic,
                           Auth auth) {
        this.producer = producer;
        this.ksqlClient = ksqlClient;
        this.bootstrapServers = bootstrapServers;
        this.topic = topic;
        this.tableName = topic.replace("-", "_") + "_table";
        this.auth = auth;
    }

    public void initialize(int partitions, int replicationFactor) {
        String streamName = topic.replace("-", "_") + "_stream";

        String createStream = String.format(
                "CREATE STREAM IF NOT EXISTS %s (" +
                "id VARCHAR KEY, " +
                "payload VARCHAR, " +
                "created_at BIGINT, " +
                "deleted BOOLEAN, " +
                "authorized_tokens VARCHAR" +
                ") WITH (KAFKA_TOPIC='%s', VALUE_FORMAT='JSON', PARTITIONS=%d, REPLICAS=%d);",
                streamName, topic, partitions, replicationFactor);

        String createTable = String.format(
                "CREATE TABLE IF NOT EXISTS %s AS " +
                "SELECT id, " +
                "LATEST_BY_OFFSET(payload) AS payload, " +
                "LATEST_BY_OFFSET(created_at) AS created_at, " +
                "LATEST_BY_OFFSET(deleted) AS deleted, " +
                "LATEST_BY_OFFSET(authorized_tokens) AS authorized_tokens " +
                "FROM %s GROUP BY id EMIT CHANGES;",
                tableName, streamName);

        logger.info("Initializing ksqlDB stream and table for topic: {}", topic);
        ksqlClient.executeStatement(createStream);
        ksqlClient.executeStatement(createTable);

        // Wait for the persistent query to be ready
        waitForTableReady();
    }

    private void waitForTableReady() {
        for (int i = 0; i < POLL_RETRIES; i++) {
            if (ksqlClient.isTableReady(tableName)) {
                logger.info("ksqlDB table {} is ready", tableName);
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warn("ksqlDB table {} may not be ready after waiting", tableName);
    }

    @Override
    public Envelope create(Envelope envelope, List<String> tokens) {
        String id = envelope.id() != null ? envelope.id() : Generators.timeBasedGenerator().generate().toString();
        Instant createdAt = Instant.now();

        Envelope newEnvelope = new Envelope(id, envelope.payload(), createdAt, false, tokens);

        try {
            String json = envelopeToJson(newEnvelope);
            producer.send(new ProducerRecord<>(topic, id, json)).get();
            producer.flush();
            awaitMaterialized(List.of(id));
            return newEnvelope;
        } catch (Exception e) {
            logger.error("Error creating document: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create document", e);
        }
    }

    @Override
    public Optional<Envelope> read(String id, List<String> tokens, Instant createdAt) {
        if (createdAt == null) {
            createdAt = Instant.now();
        }

        // Read all versions from Kafka for temporal correctness.
        // Kafka preserves all messages (versions) for a key, unlike the
        // ksqlDB materialized table which only keeps the latest.
        // After producer.send().get(), data is immediately available to consumers.
        try {
            List<Envelope> versions = readAllVersionsFromKafka(id);

            if (versions.isEmpty()) {
                return Optional.empty();
            }

            // Add 1ms buffer to account for millisecond precision differences
            Instant adjustedTimestamp = createdAt.plusMillis(1);

            // Find the latest version at or before the requested timestamp.
            // Unlike SQL databases where remove() updates in-place, Kafka appends
            // a new message with deleted=true. We must check if the LATEST version
            // is deleted (not filter deleted versions first).
            Optional<Envelope> latest = versions.stream()
                    .filter(e -> !e.createdAt().isAfter(adjustedTimestamp))
                    .max(Comparator.comparing(Envelope::createdAt));

            if (latest.isEmpty() || latest.get().deleted()) {
                return Optional.empty();
            }

            return latest
                    .filter(e -> auth == null || auth.isAuthorized(tokens, e));
        } catch (Exception e) {
            logger.error("Error reading document: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public List<Envelope> list(List<String> tokens) {
        try {
            String query = String.format(
                    "SELECT * FROM %s WHERE deleted = false;", tableName);

            // Retry to handle eventual consistency
            for (int i = 0; i < POLL_RETRIES; i++) {
                List<Map<String, Object>> results = ksqlClient.executeQuery(query);
                if (!results.isEmpty()) {
                    return results.stream()
                            .map(row -> {
                                String id = getIdFromRow(row);
                                return rowToEnvelope(id, row);
                            })
                            .filter(e -> !e.deleted())
                            .filter(e -> auth == null || auth.isAuthorized(tokens, e))
                            .collect(Collectors.toList());
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            return Collections.emptyList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Error listing documents: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public Boolean remove(String id, List<String> tokens) {
        try {
            Optional<Envelope> existing = read(id, tokens, null);
            if (existing.isEmpty()) {
                return false;
            }

            Envelope deleted = new Envelope(
                    id,
                    existing.get().payload(),
                    Instant.now(),
                    true,
                    existing.get().authorizedTokens());

            String json = envelopeToJson(deleted);
            producer.send(new ProducerRecord<>(topic, id, json)).get();
            producer.flush();
            return true;
        } catch (Exception e) {
            logger.error("Error removing document: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public List<Envelope> createMany(List<Envelope> envelopes, List<String> tokens) {
        Instant createdAt = Instant.now();
        List<Envelope> created = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        for (Envelope envelope : envelopes) {
            String id = UUID.randomUUID().toString();
            Envelope newEnvelope = new Envelope(id, envelope.payload(), createdAt, false, tokens);
            created.add(newEnvelope);

            String json = envelopeToJson(newEnvelope);
            futures.add(producer.send(new ProducerRecord<>(topic, id, json)));
        }

        try {
            for (Future<?> future : futures) {
                future.get();
            }
            producer.flush();
        } catch (Exception e) {
            logger.error("Error creating multiple documents: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create multiple documents", e);
        }

        awaitMaterialized(created.stream().map(Envelope::id).collect(Collectors.toList()));

        return created;
    }

    /**
     * Wait until the ksqlDB materialized table reflects rows already written to
     * the topic.
     *
     * <p>A write lands in Kafka, and the table {@link #list} reads is a
     * persistent query over that topic, so the table trails the write. Waiting
     * here keeps the repository read-your-writes. Without it {@code list}
     * returns whichever prefix of a write happened to materialize first: its
     * own retry loop stops at the first non-empty answer, and a partial answer
     * is non-empty.
     */
    private void awaitMaterialized(List<String> ids) {
        Set<String> pending = new HashSet<>(ids);
        String query = String.format("SELECT * FROM %s WHERE deleted = false;", tableName);

        try {
            for (int i = 0; i < POLL_RETRIES; i++) {
                for (Map<String, Object> row : ksqlClient.executeQuery(query)) {
                    pending.remove(getIdFromRow(row));
                }
                if (pending.isEmpty()) {
                    return;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            logger.warn("ksqlDB table {} still misses {} of {} rows after waiting",
                    tableName, pending.size(), ids.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Error waiting for ksqlDB materialization: {}", e.getMessage(), e);
        }
    }

    @Override
    public List<Envelope> readMany(List<String> ids, List<String> tokens) {
        List<Envelope> results = new ArrayList<>();
        for (String id : ids) {
            read(id, tokens, null).ifPresent(results::add);
        }
        return results;
    }

    @Override
    public Map<String, Boolean> removeMany(List<String> ids, List<String> tokens) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (String id : ids) {
            results.put(id, remove(id, tokens));
        }
        return results;
    }

    /**
     * Read all versions (messages) for a given key from the Kafka topic.
     * Uses assign/seekToBeginning for fast partition-level reads without group coordination.
     */
    private List<Envelope> readAllVersionsFromKafka(String id) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        List<Envelope> versions = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            // Assign partitions directly (no group coordination overhead)
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                logger.debug("No partition info for topic {}", topic);
                return versions;
            }
            List<TopicPartition> partitions = partitionInfos.stream()
                    .map(pi -> new TopicPartition(topic, pi.partition()))
                    .collect(Collectors.toList());
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            // Get end offsets to know when we've consumed everything
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            logger.debug("Topic {} endOffsets: {}", topic, endOffsets);

            // If topic is empty, return immediately
            boolean topicEmpty = endOffsets.values().stream().allMatch(offset -> offset == 0);
            if (topicEmpty) {
                logger.debug("Topic {} is empty", topic);
                return versions;
            }

            // Consume all records from beginning to end
            int pollCount = 0;
            while (pollCount < 30) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                pollCount++;
                for (ConsumerRecord<String, String> record : records) {
                    if (id.equals(record.key())) {
                        try {
                            Envelope envelope = parseKafkaRecord(record.key(), record.value());
                            versions.add(envelope);
                        } catch (Exception e) {
                            logger.warn("Failed to parse record at offset {}: {}", record.offset(), e.getMessage());
                        }
                    }
                }

                // Check if we've consumed up to all end offsets
                boolean reachedEnd = true;
                for (TopicPartition tp : partitions) {
                    long endOffset = endOffsets.getOrDefault(tp, 0L);
                    if (endOffset > 0 && consumer.position(tp) < endOffset) {
                        reachedEnd = false;
                        break;
                    }
                }
                if (reachedEnd) break;
            }
            logger.debug("Read {} versions for id {} from topic {} in {} polls", versions.size(), id, topic, pollCount);
        }
        return versions;
    }

    /**
     * Parse a Kafka record value into an Envelope.
     */
    private Envelope parseKafkaRecord(String key, String value) {
        Map<String, Object> map;
        try {
            map = Converters.MAPPER.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Kafka record value", e);
        }
        return rowToEnvelope(key, map);
    }

    private String getIdFromRow(Map<String, Object> row) {
        Object id = row.get("ID");
        if (id == null) id = row.get("id");
        return id != null ? id.toString() : null;
    }

    private String escapeId(String id) {
        return id.replace("'", "''");
    }
}

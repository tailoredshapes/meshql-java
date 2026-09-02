package com.meshql.benchmark;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.core.*;
import com.meshql.repositories.ksql.KsqlHttpClient;
import com.meshql.repositories.ksql.KsqlRepository;
import com.meshql.repositories.ksql.KsqlSearcher;
import com.meshql.repositories.mongo.MongoRepository;
import com.meshql.repositories.mongo.MongoSearcher;
import com.meshql.repos.sqlite.SQLiteRepository;
import com.meshql.repos.sqlite.SQLiteSearcher;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.tailoredshapes.stash.Stash;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.bson.Document;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

import static com.tailoredshapes.stash.Stash.stash;

public class JavaApiBenchmark {

    static final int WARMUP = 200;
    static final int ITERATIONS = 1000;
    /** The caller the benchmark runs as: an ordinary token holder. */
    static final AuthSession SESSION = new com.meshql.auth.token.TokenSession(List.of("bench"));
    static final Handlebars HB = new Handlebars();

    public static void main(String[] args) throws Exception {
        String bootstrapServers = System.getenv().getOrDefault("BOOTSTRAP_SERVERS", "localhost:9092");
        String ksqlDbUrl = System.getenv().getOrDefault("KSQL_DB_URL", "http://localhost:8088");
        boolean skipKsql = Arrays.asList(args).contains("--skip-ksql");

        System.out.println("=== MeshQL Java API Benchmark ===");
        System.out.println("Warmup: " + WARMUP + " | Iterations: " + ITERATIONS);
        System.out.println();

        // --- MongoDB (in-memory) ---
        System.out.println("Setting up MongoDB (in-memory)...");
        MongoServer mongoServer = new MongoServer(new MemoryBackend());
        String mongoConn = mongoServer.bindAndGetConnectionString();
        MongoClient mongoClient = MongoClients.create(mongoConn);
        MongoDatabase mongoDB = mongoClient.getDatabase("bench");
        MongoCollection<Document> mongoCol = mongoDB.getCollection("bench_" + UUID.randomUUID());
        Repository mongoRepo = new MongoRepository(mongoCol);
        Searcher mongoSearcher = new MongoSearcher(mongoCol);
        Template mongoFindById = HB.compileInline("{\"id\": \"{{id}}\"}");
        Template mongoFindByName = HB.compileInline("{\"payload.name\": \"{{id}}\"}");
        Template mongoFindAll = HB.compileInline("{}");

        runBenchmark("MongoDB (in-memory)", mongoRepo, mongoSearcher, mongoFindById, mongoFindByName, mongoFindAll);

        // --- SQLite (temp file) ---
        System.out.println("\nSetting up SQLite (temp file)...");
        File sqliteFile = Files.createTempFile("meshql_bench_", ".db").toFile();
        sqliteFile.deleteOnExit();
        SQLiteDataSource sqliteDS = new SQLiteDataSource();
        sqliteDS.setUrl("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
        SQLiteRepository sqliteRepo = new SQLiteRepository(sqliteDS.getConnection(), "bench");
        sqliteRepo.initialize();
        Searcher sqliteSearcher = new SQLiteSearcher(sqliteDS, "bench");
        Template sqliteFindById = HB.compileInline("id = '{{id}}'");
        Template sqliteFindByName = HB.compileInline("json_extract(payload, '$.name') = '{{id}}'");
        Template sqliteFindAll = HB.compileInline("1=1");

        runBenchmark("SQLite (temp file)", sqliteRepo, sqliteSearcher, sqliteFindById, sqliteFindByName, sqliteFindAll);

        // --- ksqlDB (Kafka + ksqlDB) ---
        KafkaProducer<String, String> producer = null;
        if (!skipKsql) {
            System.out.println("\nSetting up ksqlDB (Kafka: " + bootstrapServers + ", ksqlDB: " + ksqlDbUrl + ")...");
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            producer = new KafkaProducer<>(props);

            KsqlHttpClient ksqlClient = new KsqlHttpClient(ksqlDbUrl);
            String topic = "bench_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            KsqlRepository ksqlRepo = new KsqlRepository(producer, ksqlClient, bootstrapServers, topic);
            ksqlRepo.initialize(1, 1);
            // Wait for ksqlDB materialized table to be ready
            Thread.sleep(3000);
            Searcher ksqlSearcher = new KsqlSearcher(ksqlClient, topic);
            Template ksqlFindById = HB.compileInline("id = '{{id}}'");
            Template ksqlFindByName = HB.compileInline("EXTRACTJSONFIELD(payload, '$.name') = '{{id}}'");
            Template ksqlFindAll = HB.compileInline("1=1");

            runBenchmark("ksqlDB (Kafka)", ksqlRepo, ksqlSearcher, ksqlFindById, ksqlFindByName, ksqlFindAll);
            producer.close();
        }

        // --- Cleanup ---
        mongoClient.close();
        mongoServer.shutdown();

        System.out.println("\n=== Benchmark Complete ===");
    }

    static void runBenchmark(String label, Repository repo, Searcher searcher,
                             Template findById, Template findByName, Template findAll) throws Exception {
        System.out.println("\n--- " + label + " ---");
        System.out.printf("%-25s %8s %8s %8s %8s %8s%n",
                "Operation", "min", "avg", "med", "p95", "max");
        System.out.println("-".repeat(83));

        // Pre-create entities for read/search benchmarks
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Envelope e = repo.create(
                    new Envelope(null, stash("name", "item-" + i, "type", "A", "count", i), null, false, null),
                    SESSION);
            ids.add(e.id());
        }

        // Benchmark: create
        long[] createTimes = new long[ITERATIONS];
        for (int i = 0; i < WARMUP; i++) {
            repo.create(new Envelope(null, stash("name", "warmup-" + i, "type", "B", "count", i), null, false, null), SESSION);
        }
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            repo.create(new Envelope(null, stash("name", "bench-" + i, "type", "B", "count", i), null, false, null), SESSION);
            createTimes[i] = System.nanoTime() - start;
        }
        printStats("Repository.create()", createTimes);

        // Benchmark: read
        long[] readTimes = new long[ITERATIONS];
        for (int i = 0; i < WARMUP; i++) {
            repo.read(ids.get(i % ids.size()), SESSION, Instant.now());
        }
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            repo.read(ids.get(i % ids.size()), SESSION, Instant.now());
            readTimes[i] = System.nanoTime() - start;
        }
        printStats("Repository.read()", readTimes);

        // Benchmark: list (1250 entities after warmup+setup)
        long[] listTimes = new long[ITERATIONS];
        for (int i = 0; i < WARMUP; i++) {
            repo.list(SESSION);
        }
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            repo.list(SESSION);
            listTimes[i] = System.nanoTime() - start;
        }
        printStats("Repository.list()", listTimes);

        // Benchmark: Searcher.find (by ID)
        long[] findTimes = new long[ITERATIONS];
        for (int i = 0; i < WARMUP; i++) {
            searcher.find(findById, stash("id", ids.get(i % ids.size())), SESSION, System.currentTimeMillis());
        }
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            searcher.find(findById, stash("id", ids.get(i % ids.size())), SESSION, System.currentTimeMillis());
            findTimes[i] = System.nanoTime() - start;
        }
        printStats("Searcher.find(byId)", findTimes);

        // Benchmark: Searcher.find (by name — payload field query)
        long[] findNameTimes = new long[ITERATIONS];
        for (int i = 0; i < WARMUP; i++) {
            searcher.find(findByName, stash("id", "item-" + (i % 50)), SESSION, System.currentTimeMillis());
        }
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            searcher.find(findByName, stash("id", "item-" + (i % 50)), SESSION, System.currentTimeMillis());
            findNameTimes[i] = System.nanoTime() - start;
        }
        printStats("Searcher.find(byName)", findNameTimes);

        // Benchmark: Searcher.findAll (all entities)
        long[] findAllTimes = new long[ITERATIONS];
        for (int i = 0; i < WARMUP; i++) {
            searcher.findAll(findAll, stash(), SESSION, System.currentTimeMillis());
        }
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            searcher.findAll(findAll, stash(), SESSION, System.currentTimeMillis());
            findAllTimes[i] = System.nanoTime() - start;
        }
        printStats("Searcher.findAll()", findAllTimes);
    }

    static void printStats(String label, long[] timesNs) {
        Arrays.sort(timesNs);
        long min = timesNs[0];
        long max = timesNs[timesNs.length - 1];
        long med = timesNs[timesNs.length / 2];
        long p95 = timesNs[(int) (timesNs.length * 0.95)];
        long sum = 0;
        for (long t : timesNs) sum += t;
        long avg = sum / timesNs.length;

        System.out.printf("%-25s %7.1f %7.1f %7.1f %7.1f %7.1f  (us)%n",
                label,
                min / 1000.0, avg / 1000.0, med / 1000.0, p95 / 1000.0, max / 1000.0);
    }
}

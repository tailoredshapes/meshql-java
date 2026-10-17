package com.meshql.cert.egg;

import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Envelope;
import com.meshql.core.Plugin;
import com.meshql.core.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The harness's stand-in for a CDC: it carries each committed lay report to the hen
 * worker, at least once, in append order.
 *
 * <p>This is not meshql's business and not under test. A deployment wires this with
 * Debezium and Kafka, merkql-connect, or a trigger into a queue table; meshql has no
 * knowledge of it and the library does not start it. The cheapest thing that works on
 * every store is a poller that lists the event store and delivers the ids it has not
 * delivered yet; an id is marked delivered only after the worker accepted it.
 */
public class EventBridge implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(EventBridge.class);

    private final Repository layReports;
    private final HenWorker worker;
    private final Set<String> delivered = new HashSet<>();
    private final Thread thread;
    private volatile boolean running = true;

    public EventBridge(Plugin plugin, EggBackend backend, HenWorker worker) {
        this.layReports = plugin.createRepository(backend.storageFor().apply("lay_report"));
        this.worker = worker;
        this.thread = new Thread(this::poll, "egg-economy-event-bridge");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void poll() {
        while (running) {
            try {
                List<Envelope> events = new ArrayList<>(layReports.list(new NoAuth().systemSession()));
                events.sort(Comparator.comparing((Envelope e) -> e.createdAt() == null ? Instant.EPOCH : e.createdAt()));
                for (Envelope event : events) {
                    if (!running) {
                        return;
                    }
                    if (!delivered.contains(event.id())) {
                        worker.onLayReport(event.id());
                        delivered.add(event.id());
                    }
                }
            } catch (Exception e) {
                logger.debug("the worker could not take an event yet: {}", e.getMessage());
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        thread.interrupt();
        thread.join(2000);
    }
}

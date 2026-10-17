package com.meshql.cert.egg;

import java.util.List;

/**
 * The egg economy as data: fourteen entities in three kinds, and the references
 * between them. {@code specs/egg-economy.md} in the meshql contract is the event
 * storm this is the Java deployment of.
 *
 * <p>The schemas under {@code egg-economy/} on the classpath are the other half of
 * the deployment (one GraphQL file and one JSON Schema file per entity); this
 * table is what turns them into restlettes, graphlettes and resolvers.
 */
public final class EggEconomy {
    private EggEconomy() {
    }

    /** Who writes an entity, which decides how its restlette is declared. */
    public enum Kind {
        /** Nouns the front end writes directly. An ordinary, open restlette. */
        ACTOR,
        /** Facts the front end writes once. A {@code frozen} restlette: PUT and DELETE answer 405. */
        EVENT,
        /** Nouns only a worker writes. A restlette with {@code writers}: the front end gets 403. */
        PROJECTION
    }

    public record Entity(String name, Kind kind) {
    }

    /**
     * {@code from.fk} names a document of {@code to}. {@code forward} is the field
     * on {@code from} that follows it, {@code backward} the field on {@code to}
     * that lists the documents referring to it.
     */
    public record Ref(String from, String fk, String to, String forward, String backward) {
        /** The query on {@code from} that finds documents by this reference: hen_id is getByHen. */
        public String queryName() {
            return "getBy" + pascal(fk.substring(0, fk.length() - "_id".length()));
        }
    }

    public static final List<Entity> ENTITIES = List.of(
            new Entity("farm", Kind.ACTOR),
            new Entity("coop", Kind.ACTOR),
            new Entity("hen", Kind.ACTOR),
            new Entity("container", Kind.ACTOR),
            new Entity("consumer", Kind.ACTOR),
            new Entity("lay_report", Kind.EVENT),
            new Entity("storage_deposit", Kind.EVENT),
            new Entity("storage_withdrawal", Kind.EVENT),
            new Entity("container_transfer", Kind.EVENT),
            new Entity("consumption_report", Kind.EVENT),
            new Entity("hen_productivity", Kind.PROJECTION),
            new Entity("farm_output", Kind.PROJECTION),
            new Entity("container_inventory", Kind.PROJECTION),
            new Entity("rejection", Kind.PROJECTION));

    public static final List<Ref> REFS = List.of(
            new Ref("coop", "farm_id", "farm", "farm", "coops"),
            new Ref("hen", "coop_id", "coop", "coop", "hens"),
            new Ref("lay_report", "hen_id", "hen", "hen", "layReports"),
            new Ref("lay_report", "coop_id", "coop", "coop", "layReports"),
            new Ref("lay_report", "farm_id", "farm", "farm", "layReports"),
            new Ref("storage_deposit", "container_id", "container", "container", "deposits"),
            new Ref("storage_withdrawal", "container_id", "container", "container", "withdrawals"),
            new Ref("container_transfer", "source_container_id", "container", "sourceContainer", "transfersOut"),
            new Ref("container_transfer", "dest_container_id", "container", "destContainer", "transfersIn"),
            new Ref("consumption_report", "consumer_id", "consumer", "consumer", "consumptionReports"),
            new Ref("consumption_report", "container_id", "container", "container", "consumptionReports"),
            // hen_productivity is the hen's `productivity`, farm_output the farm's `output`
            new Ref("hen_productivity", "hen_id", "hen", "hen", "productivity"),
            new Ref("hen_productivity", "farm_id", "farm", "farm", "henProductivity"),
            new Ref("farm_output", "farm_id", "farm", "farm", "output"),
            new Ref("container_inventory", "container_id", "container", "container", "inventory"));

    public static List<Entity> of(Kind kind) {
        return ENTITIES.stream().filter(e -> e.kind() == kind).toList();
    }

    static String pascal(String snake) {
        StringBuilder out = new StringBuilder();
        for (String part : snake.split("_")) {
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}

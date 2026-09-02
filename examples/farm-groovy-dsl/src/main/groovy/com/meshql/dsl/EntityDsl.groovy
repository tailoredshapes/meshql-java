package com.meshql.dsl

import com.meshql.core.config.GraphletteConfig
import com.meshql.core.config.RestletteConfig
import com.meshql.repositories.mongo.MongoConfig
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * DSL delegate for an entity{} closure.
 * <p>
 * Configures collection name, graph endpoint, and rest endpoint for one entity.
 */
class EntityDsl {
    private static final ObjectMapper MAPPER = new ObjectMapper()
    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)

    private final String name
    private final String mongoUri
    private final String mongoDb
    private final String platformUrl
    private final int port

    private String collectionName
    private GraphletteConfig graphletteConfig
    private RestletteConfig restletteConfig

    EntityDsl(String name, String mongoUri, String mongoDb, String platformUrl, int port) {
        this.name = name
        this.mongoUri = mongoUri
        this.mongoDb = mongoDb
        this.platformUrl = platformUrl
        this.port = port
    }

    void collection(String name) {
        this.collectionName = name
    }

    void graph(String path, String schemaPath, @DelegatesTo(GraphDsl) Closure cl) {
        GraphDsl graphDsl = new GraphDsl(platformUrl)
        cl.delegate = graphDsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl.call()

        graphletteConfig = new GraphletteConfig(
                path,
                createMongoConfig(),
                schemaPath,
                graphDsl.toRootConfig()
        )
    }

    void rest(String path, String schemaPath) {
        JsonSchema jsonSchema = loadJsonSchema(schemaPath)

        restletteConfig = new RestletteConfig(
                path,
                port,
                createMongoConfig(),
                jsonSchema
        )
    }

    GraphletteConfig getGraphletteConfig() { graphletteConfig }
    RestletteConfig getRestletteConfig() { restletteConfig }

    private MongoConfig createMongoConfig() {
        MongoConfig config = new MongoConfig()
        config.uri = mongoUri
        config.db = mongoDb
        config.collection = collectionName ?: "${mongoDb.replace('_', '-')}-${name}"
        return config
    }

    private static JsonSchema loadJsonSchema(String path) {
        File file = new File(path)
        if (file.exists()) {
            def schemaNode = MAPPER.readTree(file)
            return SCHEMA_FACTORY.getSchema(schemaNode)
        }
        // Try classpath
        def stream = EntityDsl.classLoader.getResourceAsStream(path)
        if (stream) {
            def schemaNode = MAPPER.readTree(stream)
            return SCHEMA_FACTORY.getSchema(schemaNode)
        }
        return null
    }
}

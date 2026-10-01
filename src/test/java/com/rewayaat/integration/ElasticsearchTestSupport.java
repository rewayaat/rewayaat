package com.rewayaat.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.StringReader;

public abstract class ElasticsearchTestSupport {

    protected static final String INDEX = "rewayaat";
    /**
     * Where Elasticsearch is, owned by {@link TestElasticsearch} rather than started
     * here. It was started in a static block in this class, which made extending it the
     * only way to get a container — and left the one integration test that cannot extend
     * it opening a client on localhost:9200 of its own.
     */
    protected static final String elasticHost = TestElasticsearch.host();
    protected static final int elasticPort = TestElasticsearch.port();

    @DynamicPropertySource
    static void registerElasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("elasticsearch.host", () -> elasticHost);
        registry.add("elasticsearch.port", () -> elasticPort);
        registry.add("elasticsearch.index", () -> INDEX);
        registry.add("ELASTIC_HOST", () -> elasticHost);
        registry.add("ELASTIC_PORT", () -> String.valueOf(elasticPort));
        registry.add("REWAYAAT_INDEX", () -> INDEX);
    }

    protected Rest5Client restClient;
    protected ElasticsearchClient client;
    protected Rest5ClientTransport transport;

    @BeforeEach
    void resetIndex() throws Exception {
        System.setProperty("ELASTIC_HOST", elasticHost);
        System.setProperty("ELASTIC_PORT", String.valueOf(elasticPort));
        System.setProperty("REWAYAAT_INDEX", INDEX);
        // Update the static INDEX field to match the test index
        com.rewayaat.config.ESClientProvider.resetIndex();
        Rest5ClientBuilder builder = Rest5Client.builder(
                new HttpHost("http", elasticHost, elasticPort));
        restClient = builder.build();
        transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);

        if (client.indices().exists(ExistsRequest.of(b -> b.index(INDEX))).value()) {
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(INDEX)));
        }
        CreateIndexRequest request = CreateIndexRequest.of(b -> b
                .index(INDEX)
                .withJson(new StringReader("{\"settings\":{\"analysis\":{\"analyzer\":{\"search_analyzer\":{\"type\":\"standard\"}}}},\"mappings\":{\"dynamic_templates\":[{\"strings\":{\"match_mapping_type\":\"string\",\"mapping\":{\"type\":\"text\",\"fielddata\":true}}}],\"properties\":{\"topic_tags\":{\"type\":\"keyword\"}}}}")));
        client.indices().create(request);
    }

    @AfterEach
    void cleanupIndex() throws Exception {
        if (client == null) {
            return;
        }
        if (client.indices().exists(ExistsRequest.of(b -> b.index(INDEX))).value()) {
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(INDEX)));
        }
        transport.close();
        restClient.close();
    }
}

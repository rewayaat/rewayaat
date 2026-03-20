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
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.StringReader;
import java.time.Duration;

@Testcontainers
public abstract class ElasticsearchTestSupport {

    protected static final String INDEX = "rewayaat";
    private static final String ELASTIC_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:9.2.4";

    @Container
    private static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(ELASTIC_IMAGE)
                    .withEnv("discovery.type", "single-node")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withEnv("xpack.ml.enabled", "false")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("xpack.security.transport.ssl.enabled", "false")
                    .withEnv("xpack.security.http.ssl.enabled", "false")
                    .waitingFor(Wait.forHttp("/")
                            .forPort(9200)
                            .forStatusCode(200))
                    .withStartupTimeout(Duration.ofMinutes(5));

    @DynamicPropertySource
    static void registerElasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("elasticsearch.host", ELASTICSEARCH::getHost);
        registry.add("elasticsearch.port", () -> ELASTICSEARCH.getMappedPort(9200));
        registry.add("elasticsearch.index", () -> INDEX);
        registry.add("ELASTIC_HOST", ELASTICSEARCH::getHost);
        registry.add("ELASTIC_PORT", () -> String.valueOf(ELASTICSEARCH.getMappedPort(9200)));
        registry.add("REWAYAAT_INDEX", () -> INDEX);
    }

    protected Rest5Client restClient;
    protected ElasticsearchClient client;
    protected Rest5ClientTransport transport;

    @BeforeEach
    void resetIndex() throws Exception {
        System.setProperty("ELASTIC_HOST", ELASTICSEARCH.getHost());
        System.setProperty("ELASTIC_PORT", String.valueOf(ELASTICSEARCH.getMappedPort(9200)));
        System.setProperty("REWAYAAT_INDEX", INDEX);
        Rest5ClientBuilder builder = Rest5Client.builder(
                new HttpHost("http", ELASTICSEARCH.getHost(), ELASTICSEARCH.getMappedPort(9200)));
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

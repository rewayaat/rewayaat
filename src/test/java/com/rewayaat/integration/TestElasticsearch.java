package com.rewayaat.integration;

import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import java.time.Duration;

/**
 * The one Elasticsearch the tests talk to.
 *
 * <p>Started in a container when {@code -Dtestcontainers.enabled=true}, which is what CI
 * passes, and otherwise assumed to be running on {@code localhost:9200}, which is what a
 * developer has. One container for the JVM either way: Surefire reuses its fork, so the
 * static holder is started once and shared by every test that asks.
 *
 * <p>This used to live in {@link ElasticsearchTestSupport}, and the only way to get the
 * container was to extend it. {@code McpProtocolIntegrationTest} cannot — it needs its
 * own {@code @SpringBootTest} index properties — so it opened its own client on
 * {@code localhost:9200} instead and was simply excluded from CI. The moment CI stopped
 * excluding it, it failed thirty-two times with connection refused. Owning the container
 * here makes it something a test asks for rather than something it inherits.
 *
 * <p>The image is pinned to the version the cluster runs. A green build depends on this
 * container now, so a difference between it and production is a difference between a
 * green build and the site.
 */
public final class TestElasticsearch {

    private static final String IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:9.0.2";

    private static final boolean USE_CONTAINER = Boolean.parseBoolean(
            System.getProperty("testcontainers.enabled", "false"));

    private static final String HOST;
    private static final int PORT;

    static {
        String host = "localhost";
        int port = 9200;
        if (USE_CONTAINER) {
            try {
                ElasticsearchContainer container = new ElasticsearchContainer(IMAGE)
                        .withEnv("discovery.type", "single-node")
                        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                        .withEnv("xpack.ml.enabled", "false")
                        .withEnv("xpack.security.enabled", "false")
                        .withEnv("xpack.security.transport.ssl.enabled", "false")
                        .withEnv("xpack.security.http.ssl.enabled", "false")
                        .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200))
                        .withStartupTimeout(Duration.ofMinutes(5));
                container.start();
                host = container.getHost();
                port = container.getMappedPort(9200);
            } catch (Exception e) {
                // No quiet fallback. Asking for a container and getting localhost
                // instead is how a build goes green against something other than what
                // it said it was testing: both of the runs that "verified" CI's
                // testcontainers command on a developer machine had in fact fallen
                // back to the Elasticsearch already running there, and proved nothing.
                //
                // Without the flag this class never gets here and localhost is the
                // default, which is what a laptop wants. With the flag, failing to
                // start one is a failure.
                throw new IllegalStateException(
                        "testcontainers.enabled=true but Elasticsearch could not be "
                                + "started in a container: " + e.getMessage()
                                + "\nRun without -Dtestcontainers.enabled to use an "
                                + "Elasticsearch on localhost:9200 instead.", e);
            }
        }
        HOST = host;
        PORT = port;
    }

    private TestElasticsearch() {
    }

    public static String host() {
        return HOST;
    }

    public static int port() {
        return PORT;
    }
}

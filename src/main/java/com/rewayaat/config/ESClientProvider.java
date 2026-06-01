package com.rewayaat.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.hc.core5.http.HttpHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Provides a shared Elasticsearch client. When running inside a Spring context, the
 * singleton bean is initialised from application properties and shared across all
 * callers via a static reference so that legacy non-Spring code (core/, loader/) can
 * continue to use {@code new ESClientProvider()} without opening a new connection pool
 * on every call.
 */
@Component
public class ESClientProvider implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ESClientProvider.class);

    // Mutable static so that Spring's @PostConstruct can update it from config.
    public static volatile String INDEX = resolveFromEnv("REWAYAAT_INDEX", "rewayaat_updated_e5large_20260320");

    // Shared static connection – initialised from env vars at class-load time and
    // then overwritten by the Spring singleton's @PostConstruct if Spring is active.
    private static volatile ElasticsearchClient SHARED_CLIENT;
    private static volatile Rest5ClientTransport SHARED_TRANSPORT;
    private static volatile Rest5Client SHARED_REST_CLIENT;

    static {
        String host = resolveFromEnv("ELASTIC_HOST", "localhost");
        int port = parsePort(resolveFromEnv("ELASTIC_PORT", "9200"));
        initShared(host, port);
    }

    // ---- Spring-injected config (used only by the singleton bean) ----

    @Value("${elasticsearch.host:}")
    private String springHost;

    @Value("${elasticsearch.port:0}")
    private int springPort;

    @Value("${elasticsearch.index:}")
    private String springIndex;

    // ---- Per-instance client reference (may point at the shared static) ----

    private final ElasticsearchClient client;

    /**
     * Default constructor for non-Spring use (e.g. core/, loader/ classes).
     * Returns a lightweight wrapper over the shared static connection – no new
     * HTTP connection pool is created.
     */
    public ESClientProvider() {
        this.client = SHARED_CLIENT;
    }

    /**
     * Called by Spring after property injection. Re-initialises the shared connection
     * using Spring-managed configuration values, then updates the static reference so
     * that subsequently-constructed instances pick up the correct client.
     */
    @PostConstruct
    void springInit() {
        String host = (springHost != null && !springHost.isBlank())
                ? springHost : resolveFromEnv("ELASTIC_HOST", "localhost");
        int port = (springPort > 0)
                ? springPort : parsePort(resolveFromEnv("ELASTIC_PORT", "9200"));
        initShared(host, port);
        if (springIndex != null && !springIndex.isBlank()) {
            INDEX = springIndex;
        }
        LOGGER.info("Elasticsearch client initialised: {}:{} index={}", host, port, INDEX);
    }

    /** Graceful shutdown managed by Spring – closes the shared transport. */
    @PreDestroy
    void shutdown() {
        closeShared();
    }

    /**
     * No-op close so that callers using try-with-resources continue to compile without
     * modification. The shared transport is only closed by {@link #shutdown()}.
     */
    @Override
    public void close() throws IOException {
        // Intentionally a no-op for connection-lifecycle safety.
    }

    public ElasticsearchClient client() {
        return client;
    }

    /**
     * Resets the static INDEX field. This is primarily useful for tests
     * that need to override the index after system properties have been set.
     */
    public static synchronized void resetIndex() {
        INDEX = resolveFromEnv("REWAYAAT_INDEX", "rewayaat_updated_e5large_20260320");
    }

    /**
     * Resets the shared client connection to use current system properties.
     * This is primarily useful for tests that need to switch Elasticsearch
     * configurations at runtime.
     */
    public static synchronized void resetSharedClient() {
        resetIndex();
        String host = resolveFromEnv("ELASTIC_HOST", "localhost");
        int port = parsePort(resolveFromEnv("ELASTIC_PORT", "9200"));
        initShared(host, port);
    }

    // ---- Static helpers ----

    private static synchronized void initShared(String host, int port) {
        closeShared();
        Rest5ClientBuilder builder = Rest5Client.builder(new HttpHost("http", host, port));
        SHARED_REST_CLIENT = builder.build();
        SHARED_TRANSPORT = new Rest5ClientTransport(SHARED_REST_CLIENT, new JacksonJsonpMapper());
        SHARED_CLIENT = new ElasticsearchClient(SHARED_TRANSPORT);
    }

    private static void closeShared() {
        try {
            if (SHARED_TRANSPORT != null) {
                SHARED_TRANSPORT.close();
                SHARED_TRANSPORT = null;
                SHARED_REST_CLIENT = null;
                SHARED_CLIENT = null;
            }
        } catch (IOException ex) {
            LOGGER.warn("Error closing shared Elasticsearch transport", ex);
        }
    }

    private static String resolveFromEnv(String key, String defaultValue) {
        String sysProp = System.getProperty(key);
        if (sysProp != null && !sysProp.trim().isEmpty()) {
            return sysProp.trim();
        }
        String envVar = System.getenv(key);
        if (envVar != null && !envVar.trim().isEmpty()) {
            return envVar.trim();
        }
        return defaultValue;
    }

    private static int parsePort(String portValue) {
        try {
            return Integer.parseInt(portValue);
        } catch (NumberFormatException e) {
            return 9200;
        }
    }
}

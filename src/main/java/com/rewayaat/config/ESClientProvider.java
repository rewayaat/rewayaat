package com.rewayaat.config;

import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Configuration
public class ESClientProvider implements EnvironmentAware {

    private static Environment env;
    public final static String INDEX = "rewayaat";
    private static ESClientProvider instance = null;
    private static Object lock = new Object();
    private TransportClient client;

    public static ESClientProvider instance() {
        if (instance == null) {
            synchronized (lock) {
                if (null == instance) {
                    instance = new ESClientProvider();
                }
            }
        }
        return instance;
    }

    public void prepareClient() throws UnknownHostException {
        Settings settings = Settings.builder().put("client.transport.sniff", false)
                .put("cluster.name", "elasticsearch")
                .build();
        TransportClient client = new PreBuiltTransportClient(settings)
                .addTransportAddress(new TransportAddress(InetAddress.getByName(
                        System.getenv("ELASTIC_HOST")), 9300));
        this.client = client;
    }

    public void closeNode() {
        client.close();
    }

    public TransportClient getClient() throws UnknownHostException {
        if (client == null) {
            prepareClient();
        }
        return client;
    }

    public boolean isConnected() {
        try {
            return this.getClient().connectedNodes().size() > 0;
        } catch (UnknownHostException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }
}

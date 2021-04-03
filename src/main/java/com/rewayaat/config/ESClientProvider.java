package com.rewayaat.config;

import org.elasticsearch.client.Client;
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
    private Client client;

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
        Client client = new PreBuiltTransportClient(settings)
                .addTransportAddress(new TransportAddress(InetAddress.getByName(
                    env.getProperty("spring.data.elasticsearch.properties.host")
                ), Integer.parseInt(env.getProperty("spring.data.elasticsearch.properties.port"))));
        this.client = client;
    }

    public void closeNode() {
        client.close();
    }

    public Client getClient() throws UnknownHostException {
        if (client == null) {
            prepareClient();
        }
        return client;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }
}

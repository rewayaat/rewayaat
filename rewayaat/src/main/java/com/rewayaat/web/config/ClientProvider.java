package com.rewayaat.web.config;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;

@Configuration
@PropertySource("classpath:localdev.properties")
public class ClientProvider implements EnvironmentAware {

    public static String INDEX = "rewayaat";
    public static String TYPE = "rewayaat";
    private static ClientProvider instance = null;
    private static Object lock = new Object();
    private Client client;
    // default value, will be overwritten by spring configuration if applicable
    public static String host = "127.0.0.1";
    // default value, will be overwritten by spring configuration if applicable
    public static int port = 9300;

    public static ClientProvider instance() {

        if (instance == null) {
            synchronized (lock) {
                if (null == instance) {
                    instance = new ClientProvider();
                }
            }
        }
        return instance;
    }

    public void prepareClient() throws UnknownHostException {
        Settings settings = Settings.builder().put("client.transport.sniff", true).put("cluster.name", "elasticsearch")
                .build();
        TransportClient client = new PreBuiltTransportClient(settings)
                .addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(host, port)));
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

    public void printThis() {
        System.out.println(this);
    }

    @Override
    public void setEnvironment(final Environment environment) {
        port = Integer.parseInt(environment.getProperty("spring.data.elasticsearch.properties.port"));
        host = environment.getProperty("spring.data.elasticsearch.properties.host");
    }

}

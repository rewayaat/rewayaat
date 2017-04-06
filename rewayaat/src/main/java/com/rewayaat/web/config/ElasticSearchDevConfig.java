package com.rewayaat.web.config;

import java.net.InetSocketAddress;

import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

@PropertySource(value = { "classpath:localdev.properties" })
@Configuration
@Profile("dev")
@EnableElasticsearchRepositories(basePackages = "com.rewayaat.*")
public class ElasticSearchDevConfig {

    @Value("${spring.data.elasticsearch.properties.port}")
    int port;
    @Value("${spring.data.elasticsearch.properties.host}")
    String hostname;
    @Value("${spring.data.elasticsearch.cluster-name}")
    String clusterName;

    @Bean
    public ElasticsearchOperations elasticsearchTemplate() {
        ElasticsearchTemplate es = new ElasticsearchTemplate(client());
        return es;
    }

    @Bean
    public Client client() {

        System.out.println("Using profile: " + System.getenv("SPRING_PROFILES_ACTIVE"));
        Settings settings = Settings.builder().put("cluster.name", clusterName).build();
        TransportClient client = TransportClient.builder().settings(settings).build();
        client.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(hostname, port)));
        return client;
    }
}

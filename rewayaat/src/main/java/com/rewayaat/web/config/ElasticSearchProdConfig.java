package com.rewayaat.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.PropertySource;

@PropertySource(value = { "classpath:production.properties" })
@Configuration
@Profile("prod")
public class ElasticSearchProdConfig {

    @Value("${spring.data.elasticsearch.properties.port}")
    static int port;
    @Value("${spring.data.elasticsearch.properties.host}")
    static String hostname;
    @Value("${spring.data.elasticsearch.cluster-name}")
    String clusterName;
}
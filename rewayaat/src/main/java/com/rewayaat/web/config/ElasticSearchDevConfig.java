package com.rewayaat.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.PropertySource;

@PropertySource(value = { "classpath:localdev.properties" })
@Configuration
@Profile("dev")
public class ElasticSearchDevConfig {

    @Value("${spring.data.elasticsearch.properties.port}")
	static
    int port;
    @Value("${spring.data.elasticsearch.properties.host}")
	static
    String hostname;
    @Value("${spring.data.elasticsearch.cluster-name}")
    String clusterName;
}

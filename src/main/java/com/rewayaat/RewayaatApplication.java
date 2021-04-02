package com.rewayaat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;

/**
 * Main entrypoint to rewayaat app.
 */
@EnableCaching
@ComponentScan("com.rewayaat.controllers")
@SpringBootApplication
public class RewayaatApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(RewayaatApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(RewayaatApplication.class, args);
    }
}
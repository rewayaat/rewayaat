package com.rewayaat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Main entrypoint to rewayaat app.
 */
@EnableCaching
@SpringBootApplication(scanBasePackages = { "com.rewayaat.config", "com.rewayaat.controllers",
        "com.rewayaat.service" })
public class RewayaatApplication {

    public static void main(String[] args) {
        SpringApplication.run(RewayaatApplication.class, args);
    }
}
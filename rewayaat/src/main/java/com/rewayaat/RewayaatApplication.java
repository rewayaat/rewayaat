package com.rewayaat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.support.SpringBootServletInitializer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;

/**
 * Rewayaat Entrypoint.
 */
@EnableCaching
@EnableAutoConfiguration
@ComponentScan
@SpringBootApplication
public class RewayaatApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(RewayaatApplication.class, args);
    }

}
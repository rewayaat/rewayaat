package com.rewayaat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.support.SpringBootServletInitializer;

/**
 * Main Rewayaat Application.
 */
@SpringBootApplication
public class RewayaatApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(RewayaatApplication.class);
    }

    public static void main(String[] args) throws Exception {
        SpringApplication.run(RewayaatApplication.class, args);
    }

}
package com.rewayaat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.support.SpringBootServletInitializer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Rewayaat Entrypoint.
 */
@EnableCaching
@EnableAutoConfiguration
@ComponentScan
@EnableScheduling
@SpringBootApplication
public class RewayaatApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(RewayaatApplication.class, args);
    }

    @Scheduled(fixedRate = 86400000)
    public void scheduleFixedRateTask() {
        Runnable task = () -> {
            RefreshSynonymFilter.refresh();
        };

        task.run();
        // refresh the index mappings to pick up new synonyms every 24 hours.

    }
}
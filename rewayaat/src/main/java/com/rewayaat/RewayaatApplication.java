package com.rewayaat;

import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggerFactory;
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

    private static Logger log = Logger.getLogger(RewayaatApplication.class.getName(), new LoggerFactory() {
        @Override
        public Logger makeNewLoggerInstance(String name) {
            return new RewayaatLogger(name);
        }
    });

    public static void main(String[] args) {
        SpringApplication.run(RewayaatApplication.class, args);
    }


    /**
     * Refresh the index mappings to pick up new synonyms every 24 hours.
     */
    @Scheduled(fixedRate = 86400000)
    public void scheduleFixedRateTask() {
        Runnable task = () -> {
            try {
                RefreshSynonymFilter.refresh();
            } catch (Exception e) {
                log.error("Unable to refresh synonyms list!", e);
                e.printStackTrace();
            }
        };
        task.run();
    }
}
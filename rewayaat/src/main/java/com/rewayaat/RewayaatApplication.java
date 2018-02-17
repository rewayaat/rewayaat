package com.rewayaat;

import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.support.SpringBootServletInitializer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;

/**
 * Rewayaat web application entry point.
 */
@EnableCaching
@EnableAutoConfiguration
@ComponentScan
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

        // refresh the elastic search index
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
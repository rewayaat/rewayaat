package com.rewayaat.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class RewayaatHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        if (!ESClientProvider.instance().isConnected()) {
            return Health.down().build();
        }
        return Health.up().build();
    }
}

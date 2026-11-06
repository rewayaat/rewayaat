package com.rewayaat.controllers.rest;

import com.rewayaat.service.SupportCheckout;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Spring has to be able to build both, each of which also has a constructor for tests. */
class SupportCheckoutWiringTest {

    @Test
    void springCanBuildTheCheckoutAndItsController() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PropertySourcesPlaceholderConfigurer.class);
            context.register(SupportCheckout.class, SupportCheckoutController.class);
            context.refresh();
            assertNotNull(context.getBean(SupportCheckoutController.class));
            assertFalse(context.getBean(SupportCheckout.class).isOpen(), "no key in a test means closed");
        }
    }
}

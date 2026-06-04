package com.rewayaat.controllers.rest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HadithControllerMatchModeTest {

    @Test
    void preciseModeAcceptsCanonicalAndLegacyAliases() throws Exception {
        HadithController controller = new HadithController();
        Method method = HadithController.class.getDeclaredMethod("isPreciseMatchMode", String.class);
        method.setAccessible(true);

        assertEquals(true, method.invoke(controller, "precise"));
        assertEquals(true, method.invoke(controller, "strict"));
        assertEquals(true, method.invoke(controller, "exact"));
        assertEquals(false, method.invoke(controller, "flexible"));
        assertEquals(false, method.invoke(controller, "permissive"));
        assertEquals(false, method.invoke(controller, ""));
    }
}

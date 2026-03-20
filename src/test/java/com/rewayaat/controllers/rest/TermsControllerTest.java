package com.rewayaat.controllers.rest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TermsControllerTest {

    @Test
    void topTerms_rejectsShortPrefix() throws Exception {
        TermsController controller = new TermsController();
        ResponseEntity<String> response = controller.topTerms(5, "a");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}

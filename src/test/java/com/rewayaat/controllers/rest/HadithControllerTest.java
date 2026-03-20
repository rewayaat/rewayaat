package com.rewayaat.controllers.rest;

import com.rewayaat.core.data.UserAccount;
import com.rewayaat.service.AuthService;
import com.rewayaat.service.HadithEditorAccessService;
import com.rewayaat.service.HadithQueryService;
import com.rewayaat.service.SimilarHadithService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HadithController} that mock the service layer and exercise
 * the controller's own logic (parameter clamping, payload building, SSE helpers, etc.)
 * without touching Elasticsearch.
 */
@ExtendWith(MockitoExtension.class)
class HadithControllerTest {

    @Mock
    private HadithQueryService hadithQueryService;

    @Mock
    private SimilarHadithService similarHadithService;

    @Mock
    private AuthService authService;

    @Mock
    private HadithEditorAccessService hadithEditorAccessService;

    @InjectMocks
    private HadithController controller;

    @Test
    void updateHadith_requiresAuthentication() throws Exception {
        var response = controller.updateHadith(null, "hadith-1", Map.of("book", "Updated"));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void similarHadith_clampsInputsAndDelegates() throws Exception {
        controller.similarHadith("hadith-1", 0, 99);

        org.mockito.Mockito.verify(similarHadithService).findSimilar("hadith-1", 0, 25);
    }

    @Test
    void updateHadith_requiresAllowlistedEditor() throws Exception {
        UserAccount user = new UserAccount();
        user.setEmail("viewer@example.com");
        when(authService.authenticatedUser("token")).thenReturn(user);
        when(hadithEditorAccessService.canEdit("viewer@example.com")).thenReturn(false);

        var response = controller.updateHadith("token", "hadith-1", Map.of("book", "Updated"));

        assertEquals(403, response.getStatusCode().value());
    }

    // ---- GlobalExceptionHandler integration ----

    @Test
    void globalExceptionHandler_returnsOkFalseForIllegalArgument() {
        GlobalExceptionHandlerWrapper handler = new GlobalExceptionHandlerWrapper();
        var response = handler.handleIllegalArgument(new IllegalArgumentException("bad input"));
        assertEquals(400, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("ok"));
        assertEquals("bad input", body.get("message"));
    }

    @Test
    void globalExceptionHandler_returnsOkFalseForGenericException() {
        GlobalExceptionHandlerWrapper handler = new GlobalExceptionHandlerWrapper();
        var response = handler.handleGeneral(new RuntimeException("unexpected"));
        assertEquals(500, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("ok"));
    }

    /** Thin subclass to expose the protected handler methods for testing. */
    private static class GlobalExceptionHandlerWrapper extends com.rewayaat.controllers.GlobalExceptionHandler {
    }
}

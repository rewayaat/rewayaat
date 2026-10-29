package com.rewayaat.controllers;

import com.rewayaat.service.ContactFormToken;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContactPageControllerTest {

    private static ContactPageController controller() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        return new ContactPageController(messages, new ContactFormToken(""));
    }

    @Test
    void answersWithTheContactView() {
        assertEquals("contact", controller().contact(null, null, null, null,
                new ExtendedModelMap(), new MockHttpServletRequest()));
    }

    @Test
    void echoesTheContextIntoTheModel() {
        Model model = new ExtendedModelMap();
        controller().contact("narrator", "42", "Zurāra ibn Aʿyan", "https://rewayaat.info/narrator/42",
                model, new MockHttpServletRequest());
        assertEquals("narrator", model.getAttribute("about"));
        assertEquals("42", model.getAttribute("contactId"));
        assertEquals("Zurāra ibn Aʿyan", model.getAttribute("contactLabel"));
        assertEquals("https://rewayaat.info/narrator/42", model.getAttribute("contactUrl"));
        assertEquals(true, model.getAttribute("contactUrlSafe"));
        assertNotNull(model.getAttribute("contactToken"));
    }

    @Test
    void anUnknownAboutFallsBackToSite() {
        Model model = new ExtendedModelMap();
        controller().contact("<script>", null, null, null, model, new MockHttpServletRequest());
        assertEquals("site", model.getAttribute("about"));
    }

    @Test
    void anOverLongLabelIsTruncated() {
        Model model = new ExtendedModelMap();
        controller().contact(null, null, "x".repeat(1000), null, model, new MockHttpServletRequest());
        assertEquals(300, ((String) model.getAttribute("contactLabel")).length());
    }

    @Test
    void controlCharactersAreStripped() {
        assertEquals("a b c", ContactPageController.clean("a\u0000b\r\nc").replaceAll("\\s+", " "));
        assertFalse(ContactPageController.clean("a\u0007b").contains("\u0007"));
    }

    @Test
    void aJavascriptUrlIsNeverOfferedAsALink() {
        Model model = new ExtendedModelMap();
        controller().contact(null, null, null, "javascript:alert(1)", model, new MockHttpServletRequest());
        assertEquals(false, model.getAttribute("contactUrlSafe"));
        assertFalse(ContactPageController.isLinkable("//evil.example"));
    }

    @Test
    void theTemplateNeverPrintsAParameterUnescaped() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/templates/contact.html"));
        assertFalse(html.contains("th:utext"), "request parameters must not be rendered with th:utext");
        assertFalse(html.contains("[(${"), "no unescaped inline expressions");
    }
}

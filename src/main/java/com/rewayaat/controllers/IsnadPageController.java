package com.rewayaat.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.service.PageLocale;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The chain conventions: the words an isnād uses in place of a man's name.
 *
 * <p>2,731 chains in this corpus pass through «عدة من أصحابنا», and until now a reader met
 * the phrase as a step with no link and no explanation — which is the correct rendering and
 * an incomplete one. The phrase is not a gap to be apologised for; it is a convention with
 * a literature behind it, and the literature answers a question the chain raises: whose
 * companions, how many, and whether a chain is sound through them.
 *
 * <p>One page per convention, keyed by slug, content in
 * {@code static/isnad_conventions.json} so that the prose and its Arabic live together and
 * neither can be changed without the other. The JSON is read once at startup: it is
 * shipped in the jar, so a re-read per request would buy nothing.
 *
 * <p>These pages are the target of a link {@link com.rewayaat.core.ChainConventions} puts on
 * the phrase itself, wherever it stands in a chain, in either language.
 */
@Hidden
@Controller
public class IsnadPageController {

    private static final Logger LOGGER = LoggerFactory.getLogger(IsnadPageController.class);

    /** Where a convention page lives. Public URL, so the slug is transliterated, not Arabic. */
    public static final String PATH = "/isnad/";

    private final MessageSource messages;
    private final Map<String, JsonNode> bySlug;

    public IsnadPageController(MessageSource messages) {
        this.messages = messages;
        this.bySlug = load();
    }

    private static Map<String, JsonNode> load() {
        Map<String, JsonNode> conventions = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource("static/isnad_conventions.json").getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode convention : root.path("conventions")) {
                String slug = convention.path("slug").asText("");
                if (!slug.isEmpty()) {
                    conventions.put(slug, convention);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("Unable to read the isnād conventions; those pages will 404", ex);
        }
        return conventions;
    }

    @GetMapping("/isnad/{slug}")
    public String convention(@PathVariable String slug, Model model, HttpServletRequest request) {
        JsonNode convention = bySlug.get(slug);
        if (convention == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        PageLocale locale = PageLocale.of(request);
        String suffix = locale.isArabic() ? "_ar" : "_en";

        model.addAttribute("convention", convention);
        // The template asks for a field by its base name and gets the reader's language.
        // Doing it here rather than in the template keeps «_ar» out of forty expressions.
        model.addAttribute("lang", suffix);
        model.addAttribute("seoTitle", convention.path("seo_title" + suffix).asText(""));
        model.addAttribute("seoDescription", convention.path("seo_description" + suffix).asText(""));
        model.addAttribute("breadcrumbs", crumbs(locale, convention, slug));
        locale.applyTo(model, PATH + slug);
        return "isnad";
    }

    private List<Map<String, String>> crumbs(PageLocale locale, JsonNode convention, String slug) {
        String suffix = locale.isArabic() ? "_ar" : "_en";
        List<Map<String, String>> trail = new ArrayList<>();
        trail.add(Map.of("name", msg(locale, "crumb.home"), "url", locale.prefix() + "/"));
        trail.add(Map.of("name", msg(locale, "isnad.crumb"), "url", locale.prefix() + "/narrators"));
        trail.add(Map.of("name", convention.path("title" + suffix).asText(slug),
                "url", locale.prefix() + PATH + slug));
        return trail;
    }

    /** The slugs that exist, for the sitemap. */
    public List<String> slugs() {
        return new ArrayList<>(bySlug.keySet());
    }

    private String msg(PageLocale locale, String key) {
        return messages.getMessage(key, null, locale.locale());
    }
}

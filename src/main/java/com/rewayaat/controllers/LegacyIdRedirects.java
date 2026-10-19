package com.rewayaat.controllers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the v1 narration URLs of the restored books now live.
 *
 * <p>Before {@code /hadith/{id}} a narration's address was {@code /?q=_id:30611}, and
 * Search Console still holds some of them. Ghurar al-Ḥikam and Mīzān al-Ḥikma came back
 * from the v1 snapshot with their old ids, so for those - and only those - the old
 * address has an exact new one. Every other v1 id stays unmapped: a redirect to the
 * wrong narration is worse than a dead link.
 */
@Component
public class LegacyIdRedirects {

    private static final String RESOURCE = "legacy/v1_restored_ids.json";
    private static final Pattern LEGACY_QUERY = Pattern.compile("^_id:(\\S+)$");

    private final Map<String, String> targets;

    public LegacyIdRedirects() {
        this(load());
    }

    LegacyIdRedirects(Map<String, String> targets) {
        this.targets = Map.copyOf(targets);
    }

    /** The narration page a legacy {@code _id:} query now lives at, if it has one. */
    public Optional<String> target(String query) {
        if (query == null) {
            return Optional.empty();
        }
        Matcher matcher = LEGACY_QUERY.matcher(query.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(targets.get(matcher.group(1))).map(id -> "/hadith/" + id);
    }

    /**
     * Throwing on a missing resource is deliberate: the file ships in the jar, so its
     * absence is a build defect, not a runtime condition.
     */
    private static Map<String, String> load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, String>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }
}

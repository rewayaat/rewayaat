package com.rewayaat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a narrator stands against the Prophet and the Imams.
 *
 * <p>A rijal entry answers "who is this" and almost never "when was this". The books
 * themselves work the other way round - they say "a companion of al-Ṣādiq" and leave the
 * reader to know what that means - so the page carries a band that says it in a picture.
 *
 * <p>It is driven by {@code imam_ordinals} on the narrator record, which is already the
 * shape this needs: 0 for the Prophet and 1-12 for the Imams. 7,834 of the people in the
 * index carry it. A death year, where any book states one, is drawn as a marker on the same
 * axis, so a narrator whose generation and whose death year disagree shows it.
 *
 * <p><strong>No portraits.</strong> Nothing here or in {@link NarratorSeal} draws a face.
 * Shia practice does not depict the Prophet or the Imams, no likeness of any of these
 * people exists, and there are more than sixteen thousand of them - any face would be an
 * invention presented as a real person.
 *
 * <p>The spans are curated in {@code data/chronology.json}. They are the conventional
 * Twelver dates; a few are disputed in the sources, and that file is the one place to
 * correct them.
 */
@Service
public class Chronology {

    private static final Logger LOGGER = LoggerFactory.getLogger(Chronology.class);

    private final List<Era> eras;
    private final Era occultation;
    private final int axisFromAh;
    private final int axisToAh;

    public Chronology() {
        JsonNode root = read();
        JsonNode axis = root == null ? null : root.path("axis");
        this.axisFromAh = axis == null || axis.isMissingNode() ? 1 : axis.path("fromAh").asInt(1);
        this.axisToAh = axis == null || axis.isMissingNode() ? 500 : axis.path("toAh").asInt(500);
        this.eras = readEras(root == null ? null : root.path("eras"));
        this.occultation = root == null ? null : readEra(root.path("occultation"), 99);
    }

    /** One span on the band: the Prophet's period, an imamate, or the Occultation. */
    public record Era(int order, String nameEn, String nameAr, String shortEn, String shortAr,
                      int fromAh, int toAh, double leftPercent, double widthPercent,
                      boolean highlighted) {

        public Era highlight(boolean on) {
            return new Era(order, nameEn, nameAr, shortEn, shortAr, fromAh, toAh,
                    leftPercent, widthPercent, on);
        }

        /** {@code 0} is the Prophet, not an Imam; the page labels the two differently. */
        public boolean isProphet() {
            return order == 0;
        }

        /** The imamate's number in Arabic-Indic figures, «٩», as a manuscript would set it. */
        public String orderArabic() {
            StringBuilder out = new StringBuilder();
            for (char digit : String.valueOf(order).toCharArray()) {
                out.append((char) ('\u0660' + (digit - '0')));
            }
            return out.toString();
        }

        /** Where a medallion for this era sits: the middle of its span. */
        public double centerPercent() {
            return leftPercent + widthPercent / 2;
        }

        /**
         * The modifier classes for this span, built here rather than in the template.
         *
         * <p>Not a style decision - a correctness one. {@code __} is Thymeleaf's
         * preprocessing delimiter, so an expression holding two BEM names has everything
         * between the first and second {@code __} parsed as an expression of its own.
         * {@code 'hub-chrono__era--on' + 'hub-chrono__era--prophet'} inside {@code ${...}}
         * fails to parse, and the response is truncated mid-render with a 200 already sent.
         * A single name survives by accident, which is worse than failing every time.
         */
        public String cssClass() {
            StringBuilder classes = new StringBuilder();
            if (highlighted) {
                classes.append("hub-chrono__era--on");
            }
            if (isProphet()) {
                classes.append(classes.isEmpty() ? "" : " ").append("hub-chrono__era--prophet");
            }
            return classes.toString();
        }
    }

    /** A narrator placed on the band. */
    public record Placement(List<Era> eras, Era occultation, List<String> highlightedNames,
                            Integer deathAh, Double deathPercent, boolean deathOutsideScale,
                            boolean disputedDeath) {

        public boolean hasAnything() {
            return !highlightedNames.isEmpty() || deathAh != null;
        }
    }

    public List<Era> eras() {
        return eras;
    }

    public Era occultation() {
        return occultation;
    }

    /**
     * Places a narrator on the band.
     *
     * @param imamOrdinals  the {@code imam_ordinals} field: 0 for the Prophet, 1-12 for Imams
     * @param deathYears    every death year any book states, which is often none and
     *                      occasionally several that disagree
     */
    public Placement place(Collection<Integer> imamOrdinals, Collection<Integer> deathYears) {
        Set<Integer> marked = imamOrdinals == null ? Set.of() : new LinkedHashSet<>(imamOrdinals);

        List<Era> placed = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Era era : eras) {
            boolean on = marked.contains(era.order());
            placed.add(era.highlight(on));
            if (on) {
                names.add(era.nameEn());
            }
        }

        Integer death = null;
        boolean disputed = false;
        if (deathYears != null) {
            List<Integer> years = deathYears.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
            if (!years.isEmpty()) {
                // The books disagree often enough that picking silently would be a lie. The
                // earliest is drawn and the page says the others exist.
                death = years.get(0);
                disputed = years.size() > 1;
            }
        }

        return new Placement(List.copyOf(placed), occultation, List.copyOf(names),
                death,
                death == null ? null : percentOf(death),
                death != null && death > axisToAh,
                disputed);
    }

    private double percentOf(int year) {
        double span = axisToAh - axisFromAh;
        double clamped = Math.max(axisFromAh, Math.min(axisToAh, year));
        return (clamped - axisFromAh) / span * 100.0;
    }

    private List<Era> readEras(JsonNode node) {
        List<Era> loaded = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode entry : node) {
                loaded.add(readEra(entry, entry.path("order").asInt()));
            }
        }
        return List.copyOf(loaded);
    }

    private Era readEra(JsonNode n, int order) {
        if (n == null || n.isMissingNode()) {
            return null;
        }
        int from = n.path("fromAh").asInt();
        int to = n.path("toAh").asInt();
        double left = percentOf(from);
        return new Era(order,
                n.path("nameEn").asText(null), n.path("nameAr").asText(null),
                n.path("shortEn").asText(null), n.path("shortAr").asText(null),
                from, to, left, percentOf(to) - left, false);
    }

    private static JsonNode read() {
        try (InputStream in = new ClassPathResource("data/chronology.json").getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (Exception e) {
            // The band is an enrichment; the narrator page renders without it.
            LOGGER.warn("Could not read chronology.json; narrator pages will render without the band", e);
            return null;
        }
    }
}

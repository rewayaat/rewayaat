package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared helpers for loading the frozen taxonomy and validating AI-assisted tag outputs.
 */
public final class TopicTaxonomySupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TopicTaxonomySupport() {
    }

    public static List<TopicTaxonomyEntry> loadBundledTaxonomy() throws IOException {
        List<TopicTaxonomyEntry> merged = new ArrayList<>();
        try (InputStream input = TopicTaxonomySupport.class.getResourceAsStream("/static/taxonomy.json")) {
            if (input == null) {
                throw new IOException("Bundled taxonomy.json was not found on the classpath.");
            }
            JsonNode root = MAPPER.readTree(input);
            merged.addAll(parseTaxonomyEntries(root));
        }
        merged.addAll(loadSupplementalTaxonomy());
        return dedupeEntries(merged);
    }

    public static List<TopicTaxonomyEntry> parseTaxonomyProposal(String raw) throws IOException {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }
        String cleaned = raw.trim()
                .replaceFirst("^```[a-zA-Z]*\\s*", "")
                .replaceFirst("\\s*```$", "")
                .replace("]]}", "]}")  // Fix common AI error: extra ] before closing
                .trim();
        JsonNode root = MAPPER.readTree(cleaned);
        if (root.isObject() && root.has("taxonomy")) {
            root = root.get("taxonomy");
        }
        return parseTaxonomyEntries(root);
    }

    public static List<String> parseSelectedTags(String raw, Set<String> allowedSlugs) throws IOException {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }
        String cleaned = raw.trim()
                .replaceFirst("^```[a-zA-Z]*\\s*", "")
                .replaceFirst("\\s*```$", "")
                .replace("]]}", "]}")  // Fix common AI error: extra ] before closing
                .trim();

        JsonNode root;
        try {
            root = MAPPER.readTree(cleaned);
        } catch (IOException ex) {
            root = MAPPER.createArrayNode();
            for (String part : cleaned.split(",")) {
                String slug = normalizeSlug(part);
                if (!slug.isBlank()) {
                    ((ArrayNode) root).add(slug);
                }
            }
        }

        if (root.isObject()) {
            if (root.has("tags")) {
                root = root.get("tags");
            } else if (root.has("topic_tags")) {
                root = root.get("topic_tags");
            }
        }

        if (root.isArray()) {
            for (JsonNode node : root) {
                String slug = normalizeSlug(node.asText(""));
                if (!slug.isBlank() && (allowedSlugs == null || allowedSlugs.contains(slug))) {
                    selected.add(slug);
                }
            }
        }
        return List.copyOf(selected);
    }

    public static Map<String, List<String>> parseTagAssignments(String raw, Set<String> allowedSlugs) throws IOException {
        return parseTagAssignmentsWithProposals(raw, allowedSlugs).assignments();
    }

    public static ParsedTagAssignments parseTagAssignmentsWithProposals(String raw, Set<String> allowedSlugs) throws IOException {
        LinkedHashMap<String, List<String>> assignments = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return new ParsedTagAssignments(assignments, List.of());
        }
        String cleaned = raw.trim()
                .replaceFirst("^```[a-zA-Z]*\\s*", "")
                .replaceFirst("\\s*```$", "")
                .replace("]]}", "]}")  // Fix common AI error: extra ] before closing
                .trim();
        JsonNode root = MAPPER.readTree(cleaned);
        List<TopicTaxonomyEntry> proposals = parseEmbeddedTaxonomyProposals(root);
        LinkedHashSet<String> effectiveAllowed = new LinkedHashSet<>();
        if (allowedSlugs != null) {
            effectiveAllowed.addAll(allowedSlugs);
        }
        for (TopicTaxonomyEntry proposal : proposals) {
            if (proposal != null && !proposal.slug().isBlank()) {
                effectiveAllowed.add(proposal.slug());
            }
        }
        JsonNode documents = root;
        if (root.isObject()) {
            if (root.has("documents")) {
                documents = root.get("documents");
            } else if (root.has("results")) {
                documents = root.get("results");
            }
        }
        if (!documents.isArray()) {
            return new ParsedTagAssignments(assignments, proposals);
        }
        for (JsonNode document : documents) {
            if (document == null || !document.isObject()) {
                continue;
            }
            String id = cleanLabel(document.path("id").asText(""));
            if (id.isBlank()) {
                continue;
            }
            List<String> tags = parseSelectedTags(document.path("tags").toString(), effectiveAllowed);
            assignments.put(id, tags);
        }
        return new ParsedTagAssignments(assignments, proposals);
    }

    private static List<TopicTaxonomyEntry> parseEmbeddedTaxonomyProposals(JsonNode root) {
        if (root == null || !root.isObject()) {
            return List.of();
        }
        String[] fieldNames = new String[] { "proposed_taxonomy", "taxonomy_proposals", "new_taxonomy", "new_tags" };
        LinkedHashMap<String, TopicTaxonomyEntry> merged = new LinkedHashMap<>();
        for (String fieldName : fieldNames) {
            JsonNode node = root.get(fieldName);
            if (node == null || node.isNull()) {
                continue;
            }
            List<TopicTaxonomyEntry> parsed = parseTaxonomyEntries(node);
            for (TopicTaxonomyEntry entry : parsed) {
                if (entry != null && !entry.slug().isBlank()) {
                    merged.put(entry.slug(), entry);
                }
            }
        }
        return List.copyOf(merged.values());
    }

    public static Set<String> slugSet(List<TopicTaxonomyEntry> entries) {
        LinkedHashSet<String> slugs = new LinkedHashSet<>();
        if (entries == null) {
            return slugs;
        }
        for (TopicTaxonomyEntry entry : entries) {
            if (entry != null && !entry.slug().isBlank()) {
                slugs.add(entry.slug());
            }
        }
        return slugs;
    }

    public static Set<String> taggableSlugSet(List<TopicTaxonomyEntry> entries) {
        LinkedHashSet<String> slugs = new LinkedHashSet<>();
        if (entries == null) {
            return slugs;
        }
        for (TopicTaxonomyEntry entry : entries) {
            if (entry != null && entry.isTaggable() && !entry.slug().isBlank()) {
                slugs.add(entry.slug());
            }
        }
        return slugs;
    }

    public static Map<String, TopicTaxonomyEntry> indexBySlug(List<TopicTaxonomyEntry> entries) {
        Map<String, TopicTaxonomyEntry> index = new LinkedHashMap<>();
        if (entries == null) {
            return index;
        }
        for (TopicTaxonomyEntry entry : entries) {
            if (entry != null && !entry.slug().isBlank()) {
                index.put(entry.slug(), entry);
            }
        }
        return index;
    }

    public static Map<String, List<TopicTaxonomyEntry>> childrenByParent(List<TopicTaxonomyEntry> entries) {
        Map<String, List<TopicTaxonomyEntry>> children = new LinkedHashMap<>();
        if (entries == null) {
            return children;
        }
        for (TopicTaxonomyEntry entry : entries) {
            if (entry == null || entry.parentSlug().isBlank()) {
                continue;
            }
            children.computeIfAbsent(entry.parentSlug(), ignored -> new ArrayList<>()).add(entry);
        }
        children.replaceAll((ignored, value) -> List.copyOf(value));
        return children;
    }

    public static List<String> expandWithAncestors(List<String> slugs, Map<String, TopicTaxonomyEntry> taxonomyBySlug) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        if (slugs == null) {
            return List.of();
        }
        for (String slug : slugs) {
            String normalized = normalizeSlug(slug);
            if (normalized.isBlank()) {
                continue;
            }
            expanded.add(normalized);
            String parent = taxonomyBySlug == null ? "" : parentSlugOf(normalized, taxonomyBySlug);
            while (!parent.isBlank()) {
                expanded.add(parent);
                parent = parentSlugOf(parent, taxonomyBySlug);
            }
        }
        return List.copyOf(expanded);
    }

    public static String rootSlugOf(String slug, Map<String, TopicTaxonomyEntry> taxonomyBySlug) {
        String normalized = normalizeSlug(slug);
        if (normalized.isBlank() || taxonomyBySlug == null || taxonomyBySlug.isEmpty()) {
            return normalized;
        }
        String current = normalized;
        String parent = parentSlugOf(current, taxonomyBySlug);
        while (!parent.isBlank()) {
            current = parent;
            parent = parentSlugOf(current, taxonomyBySlug);
        }
        return current;
    }

    public static List<String> descendantsOf(String slug, Map<String, List<TopicTaxonomyEntry>> childrenByParent) {
        String normalized = normalizeSlug(slug);
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> descendants = new LinkedHashSet<>();
        collectDescendants(normalized, childrenByParent == null ? Collections.emptyMap() : childrenByParent, descendants);
        return List.copyOf(descendants);
    }

    private static void collectDescendants(String slug,
                                           Map<String, List<TopicTaxonomyEntry>> childrenByParent,
                                           Set<String> descendants) {
        for (TopicTaxonomyEntry child : childrenByParent.getOrDefault(slug, List.of())) {
            if (descendants.add(child.slug())) {
                collectDescendants(child.slug(), childrenByParent, descendants);
            }
        }
    }

    public static List<TopicTaxonomyEntry> loadSupplementalTaxonomy() throws IOException {
        Path path = supplementalTaxonomyPath();
        if (!Files.exists(path)) {
            return List.of();
        }
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return List.of();
        }
        return parseTaxonomyProposal(raw);
    }

    public static List<TopicTaxonomyEntry> persistSupplementalProposals(List<TopicTaxonomyEntry> proposals) throws IOException {
        if (proposals == null || proposals.isEmpty()) {
            return loadSupplementalTaxonomy();
        }
        LinkedHashMap<String, TopicTaxonomyEntry> merged = new LinkedHashMap<>();
        for (TopicTaxonomyEntry entry : loadSupplementalTaxonomy()) {
            if (entry != null && !entry.slug().isBlank()) {
                merged.put(entry.slug(), entry);
            }
        }
        for (TopicTaxonomyEntry proposal : proposals) {
            if (proposal != null && !proposal.slug().isBlank()) {
                merged.put(proposal.slug(), proposal);
            }
        }
        ArrayNode out = MAPPER.createArrayNode();
        for (TopicTaxonomyEntry entry : merged.values()) {
            out.add(serializeEntry(entry));
        }
        Path path = supplementalTaxonomyPath();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        return List.copyOf(merged.values());
    }

    public static List<String> compactPromptTaxonomy(List<TopicTaxonomyEntry> entries) {
        List<String> lines = new ArrayList<>();
        if (entries == null) {
            return lines;
        }
        for (TopicTaxonomyEntry entry : entries) {
            if (entry == null || entry.slug().isBlank() || !entry.isTaggable()) {
                continue;
            }
            StringBuilder line = new StringBuilder(entry.slug())
                    .append(" | ").append(entry.englishLabel())
                    .append(" | category=").append(entry.category());
            if (!entry.parentSlug().isBlank()) {
                line.append(" | parent=").append(entry.parentSlug());
            }
            if (!entry.description().isBlank()) {
                line.append(" | ").append(entry.description());
            }
            lines.add(line.toString());
        }
        return List.copyOf(lines);
    }

    public static String normalizeSlug(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "")
                .replaceAll("-{2,}", "-");
        return normalized == null ? "" : normalized.trim();
    }

    public static String cleanLabel(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s+", " ").trim();
    }

    public static String normalizeEnglishForMatch(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .replace("ʿ", "")
                .replace("ʾ", "")
                .replace("ʻ", "")
                .replace("’", "")
                .replace("'", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String normalizeArabicForMatch(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw
                .replaceAll("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]", "")
                .replace("\u0640", "")
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ى', 'ي')
                .replace('ة', 'ه')
                .replace('ؤ', 'و')
                .replace('ئ', 'ي')
                .replaceAll("[^\\p{IsArabic}\\s]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized;
    }

    private static List<TopicTaxonomyEntry> parseTaxonomyEntries(JsonNode root) {
        if (root == null || !root.isArray()) {
            return List.of();
        }
        Map<String, TopicTaxonomyEntry> deduped = new LinkedHashMap<>();
        for (JsonNode node : root) {
            if (node == null || !node.isObject()) {
                continue;
            }
            String slug = normalizeSlug(node.path("slug").asText(""));
            String english = cleanLabel(node.path("en").asText(""));
            String arabic = cleanLabel(node.path("ar").asText(""));
            String category = normalizeSlug(node.path("category").asText(""));
            String description = cleanLabel(node.path("description").asText(""));
            String parentSlug = normalizeSlug(node.path("parent").asText(""));
            String tagType = normalizeSlug(node.path("type").asText(""));
            boolean taggable = !node.has("taggable") || node.path("taggable").asBoolean(true);
            if (slug.isBlank() || english.isBlank() || category.isBlank()) {
                continue;
            }
            deduped.put(slug, new TopicTaxonomyEntry(slug, english, arabic, category, description, parentSlug, tagType, taggable));
        }
        return List.copyOf(deduped.values());
    }

    private static List<TopicTaxonomyEntry> dedupeEntries(List<TopicTaxonomyEntry> entries) {
        LinkedHashMap<String, TopicTaxonomyEntry> deduped = new LinkedHashMap<>();
        if (entries == null) {
            return List.of();
        }
        for (TopicTaxonomyEntry entry : entries) {
            if (entry != null && !entry.slug().isBlank()) {
                deduped.put(entry.slug(), entry);
            }
        }
        return List.copyOf(deduped.values());
    }

    private static JsonNode serializeEntry(TopicTaxonomyEntry entry) {
        var node = MAPPER.createObjectNode();
        node.put("slug", entry.slug());
        node.put("en", entry.englishLabel());
        if (!entry.arabicLabel().isBlank()) {
            node.put("ar", entry.arabicLabel());
        }
        node.put("category", entry.category());
        if (!entry.parentSlug().isBlank()) {
            node.put("parent", entry.parentSlug());
        }
        if (!entry.description().isBlank()) {
            node.put("description", entry.description());
        }
        if (!entry.tagType().isBlank() && !"primary".equals(entry.tagType())) {
            node.put("type", entry.tagType());
        }
        if (!entry.taggable()) {
            node.put("taggable", false);
        }
        return node;
    }

    private static Path supplementalTaxonomyPath() {
        String configured = firstNonEmpty(System.getProperty("TOPIC_TAGS_PROPOSAL_TAXONOMY_FILE"),
                System.getenv("TOPIC_TAGS_PROPOSAL_TAXONOMY_FILE"));
        String path = configured == null || configured.isBlank()
                ? "src/main/resources/static/taxonomy.proposals.json"
                : configured.trim();
        return Paths.get(path);
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return null;
    }

    private static String parentSlugOf(String slug, Map<String, TopicTaxonomyEntry> taxonomyBySlug) {
        TopicTaxonomyEntry entry = taxonomyBySlug.get(slug);
        return entry == null ? "" : entry.parentSlug();
    }

    /**
     * Represents a taxonomy entry with a tag type.
     * Tag types: "primary" (conceptual/thematic - for cross-matching) or "secondary" (contextual metadata - biographical/historical).
     * The tagType field defaults to "primary" if not specified.
     */
    public record TopicTaxonomyEntry(String slug, String englishLabel, String arabicLabel,
                                     String category, String description, String parentSlug, String tagType,
                                     boolean taggable) {
        public TopicTaxonomyEntry {
            // Default tagType to "primary" if null or blank
            if (tagType == null || tagType.isBlank()) {
                tagType = "primary";
            }
        }

        public boolean isTaggable() {
            return taggable;
        }
    }

    public record ParsedTagAssignments(Map<String, List<String>> assignments,
                                       List<TopicTaxonomyEntry> proposals) {
    }
}

package com.rewayaat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rewayaat.config.ESClientProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Tag Migration Tool - Phase 2 of the tag migration plan.
 *
 * Remaps 48 tags to existing taxonomy tags, strips 15 vague tags.
 * Creates a re-tag queue for hadith that end up with zero tags.
 *
 * Environment variables:
 * - REWAYAAT_INDEX: Index name (default: syn_v1)
 * - TAG_MIGRATION_DRY_RUN: Set to "true" for dry run (default: false)
 * - TAG_MIGRATION_BATCH_SIZE: Batch size for updates (default: 100)
 */
public final class TagMigrationTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = readInt("TAG_MIGRATION_BATCH_SIZE", 100);
    private static final boolean DRY_RUN = readBoolean("TAG_MIGRATION_DRY_RUN", false);

    // Tags to REMAP: old tag -> new tag
    private static final Map<String, String> REMAP_TAGS = Map.ofEntries(
            Map.entry("obligatory-prayer", "prayer"),
            Map.entry("pilgrimage", "hajj"),
            Map.entry("health-hygiene", "purification"),
            Map.entry("prayer-etiquette", "prayer"),
            Map.entry("voluntary-prayer", "prayer"),
            Map.entry("halal-haram", "halal"),
            Map.entry("funeral-prayer", "funeral-rites"),
            Map.entry("divine-knowledge", "tawhid"),
            Map.entry("kaabah", "hajj"),
            Map.entry("guardianship", "rights"),
            Map.entry("bad-company", "enjoining-good"),
            Map.entry("liability", "penalties"),
            Map.entry("ramy-al-jamarat", "hajj"),
            Map.entry("beauty", "gratitude"),
            Map.entry("aqiqah", "sacrifice"),
            Map.entry("naming", "children"),
            Map.entry("major-sins", "repentance"),
            Map.entry("good-company", "brotherhood"),
            Map.entry("travel-preparation", "travel-prayer"),
            Map.entry("music", "obscenity"),
            Map.entry("funeral-procession", "funeral-rites"),
            Map.entry("prayer-clothing", "prayer"),
            Map.entry("compensatory-prayer", "prayer"),
            Map.entry("circumcision", "purification"),
            Map.entry("li-ante", "testimony-judgment"),
            Map.entry("family-care", "parents"),
            Map.entry("hair-grooming", "dress-adornment"),
            Map.entry("good-temper", "patience"),
            Map.entry("found-property", "rights"),
            Map.entry("laziness", "heedlessness"),
            Map.entry("dogs", "food-drink"),
            Map.entry("respect-elderly", "parents"),
            Map.entry("ghayrah", "chastity"),
            Map.entry("dream-interpretation", "wisdom"),
            Map.entry("images", "halal"),
            Map.entry("water-rights", "rights"),
            Map.entry("animal-welfare", "mercy"),
            Map.entry("crescent-sighting", "fasting"),
            Map.entry("wealth-management", "livelihood"),
            Map.entry("martyrdom", "warfare-jihad"),
            Map.entry("wet-nursing", "children"),
            Map.entry("salat-jaafar", "prayer"),
            Map.entry("jizya", "rights"),
            Map.entry("jizyah", "rights"),
            Map.entry("abrogation", "guidance-misguidance"),
            Map.entry("amulet", "unseen"),
            Map.entry("homosexuality", "obscenity"),
            Map.entry("honoring", "humility"),
            Map.entry("analogy", "halal"),
            Map.entry("foolishness", "intellect"),
            Map.entry("istinjа", "purification"),
            Map.entry("racism", "equality"),
            Map.entry("duha-prayer", "prayer"),
            Map.entry("ulul-azm", "prophethood"),
            Map.entry("prophets", "prophethood"),
            Map.entry("eclipse-prayer", "prayer")
    );

    // Tags to STRIP (remove without replacement)
    private static final Set<String> STRIP_TAGS = Set.of(
            "belief",
            "good-deeds",
            "etiquette",
            "evil-behavior",
            "wicked-behavior",
            "humor",
            "manhood"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl = buildBaseUrl();
    private final String index = readString("REWAYAAT_INDEX", "syn_v1");

    public static void main(String[] args) throws Exception {
        new TagMigrationTool().run();
        System.exit(0);
    }

    private void run() throws Exception {
        System.out.println("=== Tag Migration Tool ===");
        System.out.println("Index: " + index);
        System.out.println("Dry run: " + DRY_RUN);
        System.out.println("Remap tags: " + REMAP_TAGS.size());
        System.out.println("Strip tags: " + STRIP_TAGS.size());

        // First, scan to find affected hadith
        ScanResult scan = scanAffectedHadith();
        System.out.println("Affected hadith: " + scan.totalAffected);
        System.out.println("Hadith with remapped tags: " + scan.withRemappedTags);
        System.out.println("Hadith with stripped tags only: " + scan.withStrippedOnly);

        // Create snapshot before migration
        if (!DRY_RUN) {
            createSnapshot();
        }

        // Perform migration
        MigrationResult result = performMigration(scan);
        System.out.println("=== Migration Results ===");
        System.out.println("Processed: " + result.processed);
        System.out.println("Updated: " + result.updated);
        System.out.println("Zero-tag hadith: " + result.zeroTagHadith.size());

        // Write re-tag queue
        if (!result.zeroTagHadith.isEmpty()) {
            writeRetagQueue(result.zeroTagHadith);
        }
    }

    private ScanResult scanAffectedHadith() throws Exception {
        // Build query to find hadith with tags to remap or strip
        ObjectNode query = MAPPER.createObjectNode();
        query.put("size", 0);
        query.putArray("aggs")
                .addObject()
                .putObject("filter")
                .putObject("terms")
                .putArray("field")
                .add("topic_tags");

        Set<String> allTagsToProcess = new LinkedHashSet<>();
        allTagsToProcess.addAll(REMAP_TAGS.keySet());
        allTagsToProcess.addAll(STRIP_TAGS);

        ArrayNode shouldClause = query.putObject("query").putObject("bool").putArray("should");
        for (String tag : allTagsToProcess) {
            shouldClause.addObject().putObject("term").put("topic_tags", tag);
        }

        JsonNode json = postJson("/" + encode(index) + "/_search", query.toString());
        long total = json.path("hits").path("total").path("value").asLong(0);

        return new ScanResult(total);
    }

    private MigrationResult performMigration(ScanResult scan) throws Exception {
        MigrationResult result = new MigrationResult();

        // Scroll through all documents and update
        String scrollId = null;
        try {
            JsonNode page = startScroll();
            scrollId = page.path("_scroll_id").asText("");

            while (true) {
                JsonNode hitsNode = page.path("hits").path("hits");
                if (!hitsNode.isArray() || hitsNode.isEmpty()) {
                    break;
                }
                ArrayNode hits = (ArrayNode) hitsNode;

                List<ObjectNode> bulkUpdates = new ArrayList<>();

                for (JsonNode hit : hits) {
                    String docId = hit.path("_id").asText();
                    JsonNode source = hit.path("_source");

                    List<String> currentTags = readStringArray(source.path("topic_tags"));
                    List<String> newTags = processTags(currentTags);

                    result.processed++;
                    if (!currentTags.equals(newTags)) {
                        result.updated++;
                        if (newTags.isEmpty()) {
                            result.zeroTagHadith.add(docId);
                        }

                        if (!DRY_RUN) {
                            addBulkUpdate(bulkUpdates, docId, newTags);
                        }
                    }
                }

                // Flush bulk updates
                if (!bulkUpdates.isEmpty()) {
                    flushBulkUpdates(bulkUpdates);
                }

                if (result.processed % 1000 == 0) {
                    System.out.println("Processed: " + result.processed + ", Updated: " + result.updated);
                }

                page = continueScroll(scrollId);
                scrollId = page.path("_scroll_id").asText(scrollId);
            }

        } finally {
            clearScroll(scrollId);
        }

        return result;
    }

    private List<String> processTags(List<String> currentTags) {
        Set<String> result = new LinkedHashSet<>();

        for (String tag : currentTags) {
            if (REMAP_TAGS.containsKey(tag)) {
                // Remap to new tag
                result.add(REMAP_TAGS.get(tag));
            } else if (!STRIP_TAGS.contains(tag)) {
                // Keep tag (not in strip list)
                result.add(tag);
            }
            // Strip tags are simply not added
        }

        return List.copyOf(new ArrayList<>(result));
    }

    private void addBulkUpdate(List<ObjectNode> bulkUpdates, String docId, List<String> tags) {
        ObjectNode action = MAPPER.createObjectNode();
        action.set("update", MAPPER.createObjectNode()
                .put("_index", index)
                .put("_id", docId));

        ObjectNode doc = MAPPER.createObjectNode();
        ArrayNode tagsArray = doc.putArray("topic_tags");
        for (String tag : tags) {
            tagsArray.add(tag);
        }

        ObjectNode updateNode = MAPPER.createObjectNode();
        updateNode.set("doc", doc);
        updateNode.put("doc_as_upsert", false);

        bulkUpdates.add(action);
        bulkUpdates.add(updateNode);
    }

    private long flushBulkUpdates(List<ObjectNode> bulkUpdates) throws Exception {
        if (bulkUpdates.isEmpty()) {
            return 0;
        }

        StringBuilder bulkPayload = new StringBuilder();
        for (ObjectNode node : bulkUpdates) {
            bulkPayload.append(MAPPER.writeValueAsString(node)).append('\n');
        }

        JsonNode response = postNdjson("/_bulk?filter_path=errors,items.*.update.error", bulkPayload.toString());
        if (response.path("errors").asBoolean(false)) {
            System.err.println("Bulk update had errors: " + response);
        }

        return bulkUpdates.size() / 2; // Each update is 2 lines
    }

    private void writeRetagQueue(Set<String> hadithIds) throws IOException {
        ObjectNode queue = MAPPER.createObjectNode();
        queue.put("timestamp", System.currentTimeMillis());
        queue.put("index", index);
        ArrayNode ids = queue.putArray("hadith_ids");
        for (String id : hadithIds) {
            ids.add(id);
        }

        String path = "/tmp/tag_migration_retag_queue.json";
        Files.writeString(Path.of(path), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(queue));
        System.out.println("Re-tag queue written to: " + path);
    }

    private void createSnapshot() throws Exception {
        String snapshotName = "pre-tag-migration-" + System.currentTimeMillis();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("indices", index);
        body.put("include_global_state", false);

        try {
            putJson("/_snapshot/backups/" + encode(snapshotName), body.toString());
            System.out.println("Snapshot created: " + snapshotName);
        } catch (Exception e) {
            System.err.println("Warning: Could not create snapshot: " + e.getMessage());
            System.err.println("Ensure snapshot repository 'backups' is configured.");
        }
    }

    private JsonNode startScroll() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", BATCH_SIZE);
        body.putArray("sort").add("_doc");
        ArrayNode source = body.putArray("_source");
        source.add("topic_tags");

        // Query for docs with tags to remap or strip
        Set<String> allTagsToProcess = new LinkedHashSet<>();
        allTagsToProcess.addAll(REMAP_TAGS.keySet());
        allTagsToProcess.addAll(STRIP_TAGS);

        ArrayNode should = body.putObject("query").putObject("bool").putArray("should");
        for (String tag : allTagsToProcess) {
            should.addObject().putObject("term").put("topic_tags", tag);
        }
        body.putObject("min_score").put("value", 0.01);

        return postJson("/" + encode(index) + "/_search?scroll=5m", body.toString());
    }

    private JsonNode continueScroll(String scrollId) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("scroll", "5m");
        body.put("scroll_id", scrollId == null ? "" : scrollId);
        return postJson("/_search/scroll", body.toString());
    }

    private void clearScroll(String scrollId) {
        if (scrollId == null || scrollId.isBlank()) {
            return;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("scroll_id", scrollId);
            deleteJson("/_search/scroll", body.toString());
        } catch (Exception ignored) {
        }
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpRequest request = baseRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private JsonNode postNdjson(String path, String body) throws Exception {
        HttpRequest request = baseRequest(path)
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private JsonNode putJson(String path, String body) throws Exception {
        HttpRequest request = baseRequest(path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private void deleteJson(String path, String body) throws Exception {
        HttpRequest request = baseRequest(path)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                .build();
        send(request);
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT);
    }

    private JsonNode send(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Elasticsearch request failed with status " + status + ": " + body);
        }
        if (body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(body);
    }

    private static List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode child : node) {
            String value = child.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String buildBaseUrl() {
        String host = readString("ELASTIC_HOST", "localhost");
        String port = readString("ELASTIC_PORT", "9200");
        return "http://" + host + ":" + port;
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String value = readString(key, null);
        return value == null || value.isBlank() ? defaultValue :
                "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static int readInt(String key, int defaultValue) {
        String value = readString(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String readString(String key, String defaultValue) {
        String value = firstNonEmpty(System.getProperty(key), System.getenv(key));
        return value == null || value.isBlank() ? defaultValue : value.trim();
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static class ScanResult {
        final long totalAffected;
        long withRemappedTags;
        long withStrippedOnly;

        ScanResult(long totalAffected) {
            this.totalAffected = totalAffected;
        }
    }

    private static class MigrationResult {
        long processed = 0;
        long updated = 0;
        final Set<String> zeroTagHadith = new LinkedHashSet<>();
    }
}

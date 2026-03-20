package com.rewayaat.loader.quran;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.data.QuranVerse;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads Quranic verses from quran.json into the rewayaat_quran Elasticsearch index.
 *
 * Expected JSON structure (from quran-json project):
 * {
 *   "surahs": [
 *     {
 *       "number": 1,
 *       "name": "Al-Fatihah",
 *       "englishName": "The Opening",
 *       "englishNameTranslation": "Al-Fatihah",
 *       "revelationType": "Meccan",
 *       "numberOfAyahs": 7,
 *       "ayahs": [
 *         {
 *           "number": 1,
 *           "text": "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ",
 *           "numberInSurah": 1,
 *           "juz": 1,
 *           "manzil": 1,
 *           "page": 1,
 *           "ruku": 1,
 *           "hizbQuarter": 1,
 *           "sajda": false
 *         }
 *       ]
 *     }
 *   ]
 * }
 *
 * This loader expects a separate English translation file or uses a built-in translation.
 * The Ali Quli Qarai translation is recommended for Shia scholarship alignment.
 */
public class QuranVerseLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QURAN_INDEX = "rewayaat_quran";

    private final ElasticsearchClient client;
    private final String jsonResourcePath;

    public QuranVerseLoader() {
        this.client = new ESClientProvider().client();
        this.jsonResourcePath = "/static/quran.json";
    }

    public QuranVerseLoader(String jsonResourcePath) {
        this.client = new ESClientProvider().client();
        this.jsonResourcePath = jsonResourcePath;
    }

    /**
     * Loads all Quranic verses from the bundled quran.json resource.
     */
    public void load() throws Exception {
        loadFromResource(jsonResourcePath);
    }

    /**
     * Loads Quranic verses from a filesystem path.
     */
    public void loadFromPath(String path) throws Exception {
        try (FileInputStream inputStream = new FileInputStream(path)) {
            JsonNode root = MAPPER.readTree(inputStream);
            List<QuranVerse> verses = parseQuranJson(root);

            System.out.println("Parsed " + verses.size() + " Quranic verses from JSON");

            ensureIndexExists();
            indexVerses(verses);

            System.out.println("Successfully loaded " + verses.size() + " verses into " + QURAN_INDEX);
        }
    }

    /**
     * Loads Quranic verses from a specific resource path.
     */
    public void loadFromResource(String resourcePath) throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Quran JSON resource not found: " + resourcePath);
            }

            JsonNode root = MAPPER.readTree(inputStream);
            List<QuranVerse> verses = parseQuranJson(root);

            System.out.println("Parsed " + verses.size() + " Quranic verses from JSON");

            ensureIndexExists();
            indexVerses(verses);

            System.out.println("Successfully loaded " + verses.size() + " verses into " + QURAN_INDEX);
        }
    }

    /**
     * Parses the quran.json structure into QuranVerse objects.
     */
    private List<QuranVerse> parseQuranJson(JsonNode root) {
        List<QuranVerse> verses = new ArrayList<>();
        JsonNode surahs = root.path("surahs");

        if (!surahs.isArray()) {
            throw new IllegalArgumentException("Invalid quran.json format: 'surahs' array not found");
        }

        int globalAyahIndex = 0;

        for (JsonNode surah : surahs) {
            int surahNumber = surah.path("number").asInt();
            String surahNameArabic = surah.path("name").asText("");
            String surahNameEnglish = surah.path("englishName").asText("");
            String surahNameTransliteration = surah.path("englishNameTranslation").asText("");
            String revelationType = surah.path("revelationType").asText("");

            JsonNode ayahs = surah.path("ayahs");
            if (!ayahs.isArray()) {
                continue;
            }

            for (JsonNode ayah : ayahs) {
                globalAyahIndex++;
                int ayahNumberInSurah = ayah.path("numberInSurah").asInt();
                String textArabic = ayah.path("text").asText("");
                int juz = ayah.path("juz").asInt(0);
                int page = ayah.path("page").asInt(0);
                int hizbQuarter = ayah.path("hizbQuarter").asInt(0);

                // Calculate hizb number (each hizb has 4 quarters)
                int hizbNumber = (hizbQuarter - 1) / 4 + 1;

                QuranVerse verse = QuranVerse.builder()
                        .surahNumber(surahNumber)
                        .ayahNumber(ayahNumberInSurah)
                        .ayahIndex(globalAyahIndex)
                        .textArabic(textArabic)
                        .textEnglish("") // Will be loaded from translation file
                        .surahNameArabic(surahNameArabic)
                        .surahNameEnglish(surahNameEnglish)
                        .surahNameEnglishTransliteration(surahNameTransliteration)
                        .juzNumber(juz > 0 ? juz : null)
                        .hizbNumber(hizbNumber > 0 ? hizbNumber : null)
                        .pageNumber(page > 0 ? page : null)
                        .revelationType(revelationType)
                        .build();

                verses.add(verse);
            }
        }

        return verses;
    }

    /**
     * Creates the rewayaat_quran index if it doesn't exist.
     */
    private void ensureIndexExists() throws IOException {
        if (client.indices().exists(e -> e.index(QURAN_INDEX)).value()) {
            System.out.println("Index " + QURAN_INDEX + " already exists");
            return;
        }

        System.out.println("Creating index " + QURAN_INDEX);

        client.indices().create(c -> c
                .index(QURAN_INDEX)
                .mappings(m -> m
                        .properties("surah_number", p -> p.integer(i -> i))
                        .properties("ayah_number", p -> p.integer(i -> i))
                        .properties("ayah_index", p -> p.integer(i -> i))
                        .properties("text_arabic", p -> p.text(t -> t
                                .analyzer("standard")
                                .fielddata(true)))
                        .properties("text_english", p -> p.text(t -> t
                                .analyzer("standard")
                                .fielddata(true)))
                        .properties("surah_name_arabic", p -> p.keyword(k -> k))
                        .properties("surah_name_english", p -> p.keyword(k -> k))
                        .properties("surah_name_english_transliteration", p -> p.text(t -> t))
                        .properties("juz_number", p -> p.integer(i -> i))
                        .properties("hizb_number", p -> p.integer(i -> i))
                        .properties("page_number", p -> p.integer(i -> i))
                        .properties("revelation_type", p -> p.keyword(k -> k))
                        .properties("topic_tags", p -> p.keyword(k -> k))
                )
        );

        System.out.println("Index " + QURAN_INDEX + " created successfully");
    }

    /**
     * Indexes a batch of Quranic verses using bulk API.
     */
    private void indexVerses(List<QuranVerse> verses) throws IOException {
        int batchSize = 500;
        int totalVerses = verses.size();

        for (int i = 0; i < totalVerses; i += batchSize) {
            int endIndex = Math.min(i + batchSize, totalVerses);
            List<QuranVerse> batch = verses.subList(i, endIndex);

            List<BulkOperation> operations = new ArrayList<>();
            for (QuranVerse verse : batch) {
                operations.add(BulkOperation.of(b -> b
                        .index(idx -> idx
                                .index(QURAN_INDEX)
                                .id(verse.getVerseId())
                                .document(verse)
                        )
                ));
            }

            BulkResponse response = client.bulk(b -> b.operations(operations));

            if (response.errors()) {
                System.err.println("Errors detected during bulk indexing for batch " + (i / batchSize + 1));
                response.items().forEach(item -> {
                    if (item.error() != null) {
                        System.err.println("  Error indexing " + item.id() + ": " + item.error().reason());
                    }
                });
            }

            System.out.println("Indexed batch " + (i / batchSize + 1) + " (" + (i + 1) + "-" + endIndex + " of " + totalVerses + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        // Load from filesystem for large files
        String jsonPath = "/root/git/hadi/rewayaat/src/main/resources/static/quran.json";
        QuranVerseLoader loader = new QuranVerseLoader(jsonPath);
        loader.loadFromPath(jsonPath);
    }
}

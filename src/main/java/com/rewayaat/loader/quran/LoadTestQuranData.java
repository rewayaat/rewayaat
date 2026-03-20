package com.rewayaat.loader.quran;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.data.QuranVerse;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads a small test dataset of Quranic verses with English translations.
 * Used for testing the QuranVerseTaggingTool before processing all 6,236 verses.
 */
public class LoadTestQuranData {

    private static final String INDEX = "rewayaat_quran";

    public static void main(String[] args) throws Exception {
        LoadTestQuranData loader = new LoadTestQuranData();
        loader.load();
    }

    public void load() throws Exception {
        ElasticsearchClient client = new ESClientProvider().client();

        // Check if index exists, if not create it
        boolean exists = client.indices().exists(e -> e.index(INDEX)).value();
        if (!exists) {
            System.out.println("Creating index " + INDEX + "...");
            client.indices().create(c -> c
                    .index(INDEX)
                    .mappings(m -> m
                            .properties("surah_number", p -> p.integer(i -> i))
                            .properties("ayah_number", p -> p.integer(i -> i))
                            .properties("ayah_index", p -> p.integer(i -> i))
                            .properties("text_arabic", p -> p.text(t -> t.fielddata(true)))
                            .properties("text_english", p -> p.text(t -> t.fielddata(true)))
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
            System.out.println("Index created.");
        } else {
            System.out.println("Index " + INDEX + " already exists.");
        }

        // Create test verses
        List<QuranVerse> verses = createTestVerses();
        System.out.println("Loading " + verses.size() + " test verses...");

        // Index verses
        int batchSize = 100;
        for (int i = 0; i < verses.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, verses.size());
            List<QuranVerse> batch = verses.subList(i, endIndex);

            List<BulkOperation> operations = new ArrayList<>();
            for (QuranVerse verse : batch) {
                operations.add(BulkOperation.of(b -> b
                        .index(idx -> idx
                                .index(INDEX)
                                .id(verse.getVerseId())
                                .document(verse)
                        )
                ));
            }

            BulkResponse response = client.bulk(b -> b.operations(operations));

            if (response.errors()) {
                System.err.println("Errors detected during bulk indexing");
                response.items().forEach(item -> {
                    if (item.error() != null) {
                        System.err.println("  Error indexing " + item.id() + ": " + item.error().reason());
                    }
                });
            }
        }

        System.out.println("Successfully loaded " + verses.size() + " test verses into " + INDEX);
    }

    private List<QuranVerse> createTestVerses() {
        List<QuranVerse> verses = new ArrayList<>();
        int globalIndex = 0;

        // Surah 1: Al-Fatihah (7 verses)
        String[] fatihahArabic = {
            "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ",
            "ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَـٰلَمِينَ",
            "ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ",
            "مَـٰلِكِ يَوْمِ ٱلدِّينِ",
            "إِيَّاكَ نَعْبُدُ وَإِيَّاكَ نَسْتَعِينُ",
            "ٱهْدِنَا ٱلصِّرَٰطَ ٱلْمُسْتَقِيمَ",
            "صِرَٰطَ ٱلَّذِينَ أَنْعَمْتَ عَلَيْهِمْ غَيْرِ ٱلْمَغْضُوبِ عَلَيْهِمْ وَلَا ٱلضَّآلِّينَ"
        };

        String[] fatihahEnglish = {
            "In the name of Allah, the Entirely Merciful, the Especially Merciful.",
            "All praise is due to Allah, Lord of the worlds -",
            "The Entirely Merciful, the Especially Merciful,",
            "Sovereign of the Day of Recompense.",
            "It is You we worship and You we ask for help.",
            "Guide us to the straight path -",
            "The path of those upon whom You have bestowed favor, not of those who have evoked [Your] anger or of those who are astray."
        };

        for (int i = 0; i < fatihahArabic.length; i++) {
            globalIndex++;
            verses.add(QuranVerse.builder()
                    .surahNumber(1)
                    .ayahNumber(i + 1)
                    .ayahIndex(globalIndex)
                    .textArabic(fatihahArabic[i])
                    .textEnglish(fatihahEnglish[i])
                    .surahNameArabic("الفاتحة")
                    .surahNameEnglish("Al-Fatihah")
                    .surahNameEnglishTransliteration("Al-Fatihah")
                    .juzNumber(1)
                    .hizbNumber(1)
                    .pageNumber(1)
                    .revelationType("Meccan")
                    .build());
        }

        // Surah 2: Al-Baqarah (2:255 Ayat al-Kursi and 2:256)
        globalIndex++; // Account for verses before 2:255

        verses.add(QuranVerse.builder()
                .surahNumber(2)
                .ayahNumber(255)
                .ayahIndex(globalIndex)
                .textArabic("ٱللَّهُ لَآ إِلَـٰهَ إِلَّا هُوَ ٱلْحَىُّ ٱلْقَيُّومُ ۚ لَا تَأْخُذُهُۥ سِنَةٌۭ وَلَا نَوْمٌ ۚ لَّهُۥ مَا فِى ٱلسَّمَـٰوَٰتِ وَمَا فِى ٱلْأَرْضِ ۗ مَن ذَا ٱلَّذِى يَشْفَعُ عِندَهُۥٓ إِلَّا بِإِذْنِهِۦ ۚ يَعْلَمُ مَا بَيْنَ أَيْدِيهِمْ وَمَا خَلْفَهُمْ ۖ وَلَا يُحِيطُونَ بِشَىْءٍۢ مِّنْ عِلْمِهِۦٓ إِلَّا بِمَا شَآءَ ۚ وَسِعَ كُرْسِيُّهُ ٱلسَّمَـٰوَٰتِ وَٱلْأَرْضَ ۖ وَلَا يَئُودُهُۥ حِفْظُهُمَا ۚ وَهُوَ ٱلْعَلِىُّ ٱلْعَظِيمُ")
                .textEnglish("Allah - there is no deity except Him, the Ever-Living, the Sustainer of existence. Neither drowsiness overtakes Him nor sleep. To Him belongs whatever is in the heavens and whatever is on the earth. Who is it that can intercede with Him except by His permission? He knows what is before them and what is behind them, and they encompass not a thing of His knowledge except for what He wills. His Kursi (throne) extends over the heavens and the earth, and their preservation tires Him not. And He is the Most High, the Most Great.")
                .surahNameArabic("البقرة")
                .surahNameEnglish("Al-Baqarah")
                .surahNameEnglishTransliteration("Al-Baqarah")
                .juzNumber(3)
                .hizbNumber(2)
                .pageNumber(51)
                .revelationType("Medinan")
                .build());

        globalIndex++;

        verses.add(QuranVerse.builder()
                .surahNumber(2)
                .ayahNumber(256)
                .ayahIndex(globalIndex)
                .textArabic("لَآ إِكْرَاهَ فِى ٱلدِّينِ ۖ قَد تَّبَيَّنَ ٱلرُّشْدُ مِنَ ٱلْغَىِّ ۚ فَمَن يَكْفُرْ بِٱلطَّـٰغُوتِ وَيُؤْمِن بِٱللَّهِ فَقَدِ ٱسْتَمْسَكَ بِٱلْعُرْوَةِ ٱلْوُثْقَىٰ لَا ٱنفِصَامَ لَهَا ۗ وَٱللَّهُ سَمِيعٌ عَلِيمٌ")
                .textEnglish("There shall be no compulsion in the religion; the right way has become clearly distinct from the wrong; so whoever rejects the taghut and believes in Allah, he indeed has laid hold on the firmest handle, which shall not break off, and Allah is Hearing, Knowing.")
                .surahNameArabic("البقرة")
                .surahNameEnglish("Al-Baqarah")
                .surahNameEnglishTransliteration("Al-Baqarah")
                .juzNumber(3)
                .hizbNumber(2)
                .pageNumber(51)
                .revelationType("Medinan")
                .build());

        // Surah 112: Al-Ikhlas (4 verses) - skip to verse index around 6230
        globalIndex = 6230;

        String[] ikhlasArabic = {
            "قُلْ هُوَ ٱللَّهُ أَحَدٌ",
            "ٱللَّهُ ٱلصَّمَدُ",
            "لَمْ يَلِدْ وَلَمْ يُولَدْ",
            "وَلَمْ يَكُن لَّهُۥ كُفُوًا أَحَدٌۢ"
        };

        String[] ikhlasEnglish = {
            "Say, 'He is Allah, [who is] One,'",
            "Allah, the Eternal Refuge.",
            "He neither begets nor is born,",
            "Nor is there to Him any equivalent.'"
        };

        for (int i = 0; i < ikhlasArabic.length; i++) {
            globalIndex++;
            verses.add(QuranVerse.builder()
                    .surahNumber(112)
                    .ayahNumber(i + 1)
                    .ayahIndex(globalIndex)
                    .textArabic(ikhlasArabic[i])
                    .textEnglish(ikhlasEnglish[i])
                    .surahNameArabic("الإخلاص")
                    .surahNameEnglish("Al-Ikhlas")
                    .surahNameEnglishTransliteration("Al-Ikhlas")
                    .juzNumber(30)
                    .hizbNumber(60)
                    .pageNumber(604)
                    .revelationType("Meccan")
                    .build());
        }

        return verses;
    }
}

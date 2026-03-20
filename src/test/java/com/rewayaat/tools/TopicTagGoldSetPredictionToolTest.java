package com.rewayaat.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopicTagGoldSetPredictionToolTest {

    @TempDir
    Path tempDir;

    @Test
    void predict_usesSharedSeedSupportAndPrunesAncestors() throws Exception {
        Path gold = tempDir.resolve("gold.jsonl");
        Files.writeString(gold, """
                {"id":"doc-1","book":"Book of Purity","chapter":"Water; Its Purity and Impurity","english":"Narration about pure and impure water.","arabic_excerpt":""}
                {"id":"doc-2","book":"Book of Pilgrimage","chapter":"Visiting the shrine of Imam Husayn","english":"Narration about shrine visitation.","arabic_excerpt":""}
                """, StandardCharsets.UTF_8);

        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.parseTaxonomyProposal("""
                {"taxonomy":[
                  {"slug":"purification","en":"Purification","category":"worship"},
                  {"slug":"water-purity","en":"Water Purity and Impurity","category":"worship","parent":"purification"},
                  {"slug":"ziyarat","en":"Ziyarat","category":"devotion"}
                ]}
                """);

        TopicTagGoldSetPredictionTool tool = new TopicTagGoldSetPredictionTool(gold, tempDir.resolve("predicted.jsonl"));

        List<ObjectNode> predictions = tool.predict(taxonomy);

        assertEquals(2, predictions.size());
        assertEquals("[\"water-purity\"]", predictions.get(0).withArray("predicted_topic_tags").toString());
        assertEquals("[\"ziyarat\"]", predictions.get(1).withArray("predicted_topic_tags").toString());
    }

    @Test
    void predict_refinesKnownQaProblemPatterns() throws Exception {
        Path gold = tempDir.resolve("gold-problems.jsonl");
        Files.writeString(gold, """
                {"id":"doc-1","book":"Kāmil al-Ziyārāt","chapter":"The Ziyārah of the Prophet (s.a.a.w.), Invocations Next to His Grave, and Performance of the Ziyārah","english":"After you finish your invocations next to the grave of the Prophet, go to the pulpit and establish prayers in the Mosque of the Prophet.","arabic_excerpt":""}
                {"id":"doc-2","book":"Mu'jam","chapter":"The Status of the Umm al-Kitab Mother of the Book","english":"Reciting the Quran every day and the status of the Mother of the Book.","arabic_excerpt":""}
                {"id":"doc-3","book":"Al-Tawḥīd","chapter":"There is no god but the One God","english":"There is no god but God, the One God, and this is a proof of divine unity.","arabic_excerpt":""}
                {"id":"doc-4","book":"Al-Tawḥīd","chapter":"Knowledge","english":"The chapter is in Al-Tawhid and discusses the divine attributes of Allah.","arabic_excerpt":""}
                {"id":"doc-5","book":"Al-Kāfi","chapter":"The Excellence of Fast and Fasting","english":"Islam is founded on salat, zakat, hajj, fasting and al-wilayat. Fasting is a shield against the fire.","arabic_excerpt":""}
                {"id":"doc-6","book":"Al-Kāfi","chapter":"The Rights of a Believer on his Brother (in belief)","english":"Of the rights of the believer on his believing brother is to satisfy his hunger and pay off his debts.","arabic_excerpt":""}
                {"id":"doc-7","book":"Al-Kāfi","chapter":"Precious Ahadith on Conditions of Disappearance from Public Sight of the Twelfth Imam","english":"The leader would be out of their sight and they await his reappearance.","arabic_excerpt":""}
                {"id":"doc-8","book":"Al-Khiṣāl","chapter":"God is One and Only","english":"A Bedouin stood near the Commander of the Faithful Imam Ali and asked whether God is One.","arabic_excerpt":""}
                {"id":"doc-9","book":"Nahj al-Balāgha","chapter":"Instructions for Hasan, when returning from Siffin","english":"He wrote for al-Hasan ibn Ali on his return from Siffin.","arabic_excerpt":""}
                {"id":"doc-10","book":"Al-Kāfi","chapter":"The people of Dhikr that Allah commanded the creatures to ask","english":"The Prophet said, I am the Dhikr and the Imams are the people of Dhikr.","arabic_excerpt":""}
                {"id":"doc-11","book":"Al-Kāfi","chapter":"Enlightening Points Deduced from the Holy Quran about Leadership with Divine Authority","english":"The verse means the wilayah of Ali and the attentive ears from the Quranic verse refer to him.","arabic_excerpt":""}
                {"id":"doc-12","book":"Kitāb al-Zuhd","chapter":"The Rights of Neighbors","english":"A man said he had a neighbor who harmed him, and the Prophet warned against harming one’s neighbors.","arabic_excerpt":""}
                {"id":"doc-13","book":"Kitāb al-Ghayba","chapter":"Mahdi is from the progeny of Imam Husain","english":"Amir al-Muminin said Allah will bring forth from Husayn's seed a man who will rise after occultation.","arabic_excerpt":""}
                """, StandardCharsets.UTF_8);

        List<TopicTaxonomySupport.TopicTaxonomyEntry> taxonomy = TopicTaxonomySupport.parseTaxonomyProposal("""
                {"taxonomy":[
                  {"slug":"knowledge","en":"Knowledge","category":"ethics"},
                  {"slug":"parents","en":"Parents","category":"family"},
                  {"slug":"family","en":"Family","category":"family"},
                  {"slug":"quran","en":"Quran","category":"knowledge"},
                  {"slug":"funeral-rites","en":"Funeral Rites","category":"worship"},
                  {"slug":"prayer","en":"Prayer","category":"worship"},
                  {"slug":"ziyarat","en":"Ziyarat","category":"devotion"},
                  {"slug":"prophet-muhammad","en":"Prophet Muhammad","category":"persons"},
                  {"slug":"good-character","en":"Good Character","category":"ethics"},
                  {"slug":"faith","en":"Faith","category":"belief"},
                  {"slug":"tawhid","en":"Tawhid","category":"belief","parent":"faith"},
                  {"slug":"fasting","en":"Fasting","category":"worship"},
                  {"slug":"rights","en":"Rights","category":"social"},
                  {"slug":"brotherhood","en":"Brotherhood","category":"social"},
                  {"slug":"wilayah","en":"Wilayah","category":"belief"},
                  {"slug":"imamate","en":"Imamate","category":"belief"},
                  {"slug":"occultation","en":"Occultation","category":"belief","parent":"imamate"},
                  {"slug":"imam-mahdi","en":"Imam Mahdi","category":"belief","parent":"imamate"},
                  {"slug":"imam-ali","en":"Imam Ali","category":"persons"},
                  {"slug":"imam-hasan","en":"Imam Hasan","category":"persons"},
                  {"slug":"remembrance","en":"Remembrance","category":"devotion"}
                ]}
                """);

        TopicTagGoldSetPredictionTool tool = new TopicTagGoldSetPredictionTool(gold, tempDir.resolve("predicted-problems.jsonl"));
        List<ObjectNode> predictions = tool.predict(taxonomy);

        assertEquals("[\"ziyarat\",\"prophet-muhammad\"]", predictions.get(0).withArray("predicted_topic_tags").toString());
        assertEquals("[]", predictions.get(1).withArray("predicted_topic_tags").toString());
        assertEquals("[\"tawhid\"]", predictions.get(2).withArray("predicted_topic_tags").toString());
        assertEquals("[\"tawhid\"]", predictions.get(3).withArray("predicted_topic_tags").toString());
        assertEquals("[\"fasting\"]", predictions.get(4).withArray("predicted_topic_tags").toString());
        assertEquals("[\"rights\",\"brotherhood\"]", predictions.get(5).withArray("predicted_topic_tags").toString());
        assertEquals("[\"occultation\",\"imam-mahdi\"]", predictions.get(6).withArray("predicted_topic_tags").toString());
        assertEquals("[\"tawhid\"]", predictions.get(7).withArray("predicted_topic_tags").toString());
        assertEquals("[\"imam-hasan\",\"good-character\"]", predictions.get(8).withArray("predicted_topic_tags").toString());
        assertEquals("[\"imamate\"]", predictions.get(9).withArray("predicted_topic_tags").toString());
        assertEquals("[\"wilayah\",\"quran\"]", predictions.get(10).withArray("predicted_topic_tags").toString());
        assertEquals("[\"rights\"]", predictions.get(11).withArray("predicted_topic_tags").toString());
        assertEquals("[\"occultation\",\"imam-mahdi\"]", predictions.get(12).withArray("predicted_topic_tags").toString());
    }
}

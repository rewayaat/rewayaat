package com.rewayaat.loader.kitab_al_tawheed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Files;
import com.rewayaat.config.ClientProvider;
import com.rewayaat.core.data.HadithObject;
import com.rewayaat.loader.LoaderUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.elasticsearch.client.transport.NoNodeAvailableException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Must be executed on linux machine with the pdftopom package installed.
 */
public class KitabAlTawheedWorker extends Thread {
    private String[] chapterNamesArray = {
            "n/a",
            "Reward for the Monotheists and Gnostics",
            "Divine Unity and Negation of Anthropomorphism",
            "The Definition of One, Divine Unity, and the Believer in Divine Unity",
            "The Commentary of Chapter 112 the Unity",
            "The Meaning of Divine Unity and Divine Justice",
            "The Mighty and High is Devoid of both Body and Image",
            "The Blessed and Exalted is a Thing",
            "What is Related Regarding the Vision",
            "Al-Qudrah Omnipotence",
            "Al-Ilm Knowledge",
            "Attributes of Essence and Attributes of Actions",
            "The Commentary of verse 88 of Chapter 28 the Narrative [al-Qasas] 'Everything is perishable but He'",
            "The Commentary of Verse 75 of Chapter 38 Sad O Iblis! What prevented you that you should do obeisance to him whom I created with My Two Hands?",
            "The Commentary of Verse 42 of Chapter 68 Qalam - On the Day when there shall " +
                    "be a severe affliction, and they shall be called upon to make obeisance",
            "The Commentary of Verse 35 of Chapter 24 the Light [al-Nur]",
            "The Commentary of Verse 67 of Chapter 9 the Repentance [al-Tawbah] They have " +
                    "forsaken Allah, so He has forsaken them",
            "The Commentary of Verse 67 of Chapter 39 the Companies [al-Zumar] And the " +
                    "whole Earth shall be in His Grip on the Day of Resurrection and the Heavens rolled up in his Right " +
                    "Hand",
            "The Commentary of Verse 15 of Chapter 83 the Defrauders [al-Mutaffifin] Nay! " +
                    "Most surely they shall on that day be debarred from their Lord",
            "The Commentary of Verse 22 of Chapter 89 the Daybreak [al-Fajr] And your Lord " +
                    "comes and (also) the angels in ranks",
            "The Commentary of Verse 210 of Chapter 2 the Cow [al-Baqarah] They do not wait " +
                    "aught but that Allah should come to them in the shadow of clouds along with the angels.",
            "Meaning of Scoffing, Mockery, Planning and Deception of Allah",
            "The Meaning of Allah’s Side",
            "The Meaning of the Waistband",
            "The Meaning of the Eye, the Ear, and the " +
                    "Tongue of Allah",
            "The Meaning of Allah’s Hand is Tied Up",
            "The Meaning of His Pleasure and His Anger",
            "The Meaning of Allah’s Breathing of Spirit And " +
                    "I breathed into him of My Sprit",
            "Negation of Space, Time, Stillness, Motion, Descending, Ascending, " +
                    "and Transference from Allah",
            "The Names of Allah, the Exalted, and the Difference between their Meanings and the " +
                    "Meaning of the Names of Creation",
            "What is the Qur’an?",
            "The Meaning of “In the Name of Allah, " +
                    "the Most Compassionate, the Most Merciful”",
            "The Explanation of the Letters of the Alphabet",
            "The Explanation of the Letters of the Alphabet " +
                    "According to Their Numerical Value",
            "The Explanation of the Words of the Calls " +
                    "to Prayer",
            "The " +
                    "Commentary of Guidance, Misguidance, Direction, and Forsaking is from Allah, the Exalted",
            "The Refutation of the Dualists & " +
                    "the Atheists",
            "The Refutation of the Ones who Say that Allah is the Third of the Three: There is no god " +
                    "but the One God",
            "The Remembrance of Allah’s Greatness, " +
                    "Mighty be His Glory",
            "The Subtlety of Allah, the Blessed, the " +
                    "Exalted",
            "The Least Required for Recognizing Diving " +
                    "Unity",
            "He, the Mighty and High, in not " +
                    "Recognized, Except by Himself",
            "The Assertion of the Emergence of the Universe",
            "The Tradition of Dhi`lib",
            "The Tradition of Subakht, the Jewish Man",
            "The Meaning of “Glory be to Allah”",
            "The Meaning of Allah is the Greatest",
            "The Meaning of the First and the Last",
            "The Meaning of Allah`s " +
                    "Word: the Most Compassionate is Firm on the Empyrean",
            "The Meaning of Allah’s Word: " +
                    "His Empyrean was on the Water",
            "The Empyrean and Its Description",
            "The Empyrean was Created in Quarters",
            "The Meaning of 'His " +
                    "Knowledge Extend over the Heavens and the Earth'",
            "Allah made the Nature of " +
                    "the Creation upon Divine Unity",
            "Al-Bada’ The Appearance",
            "The Will and the Intent",
            "Al-Istita`ah Capability",
            "The Trial and the Test",
            "Privilege and Adversity",
            "The Negation of Determinism and Relinquishment",
            "Predestination, Divine Decree, Trials, Means of Sustenance Rates, and Restricted Powers",
            "Fihim Children and Allah’s Justice Concerning Them",
            "Allah Only Des What is " +
                    "Best for His Servant",
            "Command, Prohibition, Promise " +
                    "and Threat",
            "Recognition, Explanation, " +
                    "Evidence, and Guidance",
            "A Session of Imam Rida (AS) with Theologians from Among the Rhetoricians and Various " +
                    "Religions about Unity in the presence of al-Ma`mun",
            "A Session of Imam Rida (AS) with Sulayman al-Marwazi, the Theologian of Khurasan, in the " +
                    "Presece of al-Ma`mun concerning the Subject of Divine Unity",
            "The Prohibition of " +
                    "Discussing, Debating, and Arguing about Allah"
    };
    private String chapter = this.chapterNamesArray[29];
    private String book = "Kitab Al-Tawhid | كتاب التوحيد";
    private List<HadithObject> hadithObjects = new ArrayList<HadithObject>();
    private int start;
    private int end;

    public KitabAlTawheedWorker(int start, int end) {
        this.start = start;
        this.end = end;
    }

    @Override
    public void run() {
        PrintWriter writer = null;
        try {
            writer = new PrintWriter((new BufferedWriter(new FileWriter(
                    "/home/zir0/git/rewayaatv2/rewayaat/src/main/java/com/rewayaat/loader/kitab_al_tawheed/operationLog_"
                            + start + "-" + end + ".txt",
                    true))));
        } catch (IOException e1) {

            e1.printStackTrace();
        }
        File myTempDir = Files.createTempDir();
        PDDocument document = null;
        String pdfLocation = "/ssd/onedrive/Documents/Books/Tawhid.pdf";
        try {
            document = PDDocument.load(new File(pdfLocation));
        } catch (InvalidPasswordException e1) {
            e1.printStackTrace();
        } catch (IOException e1) {
            e1.printStackTrace();
        }
        PDFTextStripper reader = null;
        try {
            reader = new PDFTextStripper();
        } catch (IOException e1) {
            e1.printStackTrace();
        }

        for (int i = start; i < end; i++) {
            writer.println("Processing page: " + i);
            try {
                reader.setStartPage(i);
                reader.setEndPage(i);
                String st = reader.getText(document);
                String[] lines = st.split("\n");
                for (int j = 0; j < lines.length; j++) {
                    String line = lines[j];
                    if (!LoaderUtil.containsArabic(line)) {
                        if (!line.trim().isEmpty()) {
                            if (line.contains("Translator’s Note")
                                    || line.contains("Editor’s Note")
                                    || line.trim().matches("[0-9]+")) {
                                if (line.trim().matches("[0-9]+")) {
                                    continue;
                                } else {
                                    break;
                                }
                            } else if (line.toUpperCase().trim().startsWith("CHAPTER ")) {
                                if (!hadithObjects.isEmpty()) {
                                    saveHadith();
                                }
                                chapter = "";
                                chapter += line.trim();
                                while (!lines[j + 1].trim().isEmpty() && !LoaderUtil.containsArabic(lines[j + 1])) {
                                    chapter += " " + lines[j + 1].trim();
                                    j++;
                                }
                                if (chapter.contains(":")) {
                                    String wordNumber = chapter.substring(0, chapter.indexOf(":")).toLowerCase().replaceAll("chapter", "").trim();
                                    int chapterInteger = (int) LoaderUtil.convertWordToInteger(wordNumber);
                                    chapter = "Chapter " + String.valueOf(chapterInteger) + " - " + this.chapterNamesArray[chapterInteger].trim();
                                }
                                chapter = LoaderUtil.cleanupText(chapter).trim();
                                writer.println(chapter);
                                writer.flush();
                            } else if (line.trim().matches("^[0-9]+\\..*$")) {
                                if (!hadithObjects.isEmpty()) {
                                    saveHadith();
                                }
                                setupNewHadithObj();
                                getNewestHadith().setNumber(line.trim().substring(0, line.trim().indexOf(".")));
                                getNewestHadith().insertEnglishText(
                                        line.trim().substring(line.trim().indexOf(".") + 1).trim() + " ");
                            } else {
                                if (!hadithObjects.isEmpty()) {
                                    getNewestHadith().insertEnglishText(line.trim() + " ");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                writer.println("Error while processing current Hadith:\n" + getNewestHadith().toString() + "\n");
                writer.flush();
                e.printStackTrace(writer);
                continue;
            }
            writer.println("Finished Processing page: " + i);
            writer.flush();
        }
        if (!hadithObjects.isEmpty()) {
            try {
                saveHadith();
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }
        writer.close();
        myTempDir.delete();

    }

    public void saveHadith() throws JsonProcessingException {

        ObjectMapper mapper = new ObjectMapper();
        HadithObject completedHadith = completeOldestHadith();
        completedHadith.setEnglish(LoaderUtil.cleanupText(completedHadith.getEnglish()));
        byte[] json = mapper.writeValueAsBytes(completedHadith);
        boolean successful = false;
        int tries = 0;
        while (successful == false && tries < 8) {
            try {
                ClientProvider.instance().getClient().prepareIndex(ClientProvider.INDEX, ClientProvider.TYPE)
                        .setSource(json).get();
                successful = true;
            } catch (NoNodeAvailableException e) {
                tries++;
                continue;
            }
        }
    }

    public void setupNewHadithObj() {
        HadithObject currentHadith = new HadithObject();
        if (chapter != null) {
            currentHadith.setChapter(chapter);
        }
        currentHadith.setBook(book);
        hadithObjects.add(currentHadith);
    }

    private HadithObject getOldestHadith() {
        return hadithObjects.get(0);
    }

    private HadithObject getNewestHadith() {
        return hadithObjects.get(hadithObjects.size() - 1);
    }

    private HadithObject completeOldestHadith() {
        HadithObject hadith = hadithObjects.get(0);
        hadith.setEnglish(LoaderUtil.cleanupText(hadith.getEnglish()));
        hadithObjects.remove(0);
        return hadith;
    }

}
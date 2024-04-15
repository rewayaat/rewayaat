package com.rewayaat.loader.KamilAlZiyarat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
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
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class KamilAlZiyaratWorker {

    private static String book = "Kamil Al-Ziyarat | كامل الزيارات";
    private static List<HadithObject> hadithObjects = new ArrayList<HadithObject>();
    private static String chapter = "";
    private static String operationLogPath = "/home/zir0/git/rewayaatv2/src/main/java/com/rewayaat/loader/KamilAlZiyarat/operationLog.txt";
    private static Map<String, Map<String, HadithObject>> hadithMap = new HashMap();
    private static PrintWriter writer = null;

    public static void main(String[] args) {
        try {
            File operationLog = new File(operationLogPath);
            boolean result = Files.deleteIfExists(operationLog.toPath());
            writer = new PrintWriter((new BufferedWriter(new FileWriter(
                    operationLogPath,
                    true))));
        } catch (IOException e1) {

            e1.printStackTrace();
        }
        PDDocument document = null;
        String pdfLocation = "/ssd/onedrive/Documents/Books/Hadith Books/KamiluzZiaraat.pdf";
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

        for (int i = 8; i < 373; i++) {
            writer.println("Processing page: " + i);
            try {
                reader.setStartPage(i);
                reader.setEndPage(i);
                String st = reader.getText(document);
                String[] lines = st.split("\n");
                for (int j = 0; j < lines.length; j++) {
                    String line = lines[j];
                    if (!line.trim().isEmpty() && !LoaderUtil.containsArabic(line)) {
                        if (line.trim().matches("[0-9]+")) {
                            continue;
                        } else if (line.toUpperCase().trim().startsWith("CHAPTER ")) {
                            if (line.trim().startsWith("Chapter 28")) {
                                chapter = "Chapter 28 - Lamentation of Heavens and Earth over Killing of Imam Husain and Yahya bin Zakariya (a.s.)";
                            } else {
                                chapter = line.trim().replace(":", " -");
                                while (!lines[j + 1].trim().contains("Tradition")) {
                                    chapter += " " + lines[j + 1].trim();
                                    j++;
                                }
                                chapter = LoaderUtil.cleanupText(chapter).trim();
                            }
                            writer.println(chapter);
                            writer.flush();
                        } else if (line.trim().matches("^Tradition [0-9]+:.*$")) {
                            setupNewHadithObj();
                            getNewestHadith().setNumber(line.trim().substring(9, line.trim().indexOf(":")).trim());
                            String hadithText = line.trim().substring(line.trim().indexOf(":") + 1).trim();
                            if (hadithText.trim().matches("^Same as no\\.? *[0-9]+ *\\.?$")) {
                                String sameAsNo = hadithText.replaceAll("[^0-9]", "").trim();
                                String originalHadithText = getHadithEnglishText(chapter, sameAsNo);
                                if (originalHadithText != null && !originalHadithText.isEmpty()) {
                                    getNewestHadith().insertEnglishText(originalHadithText.trim());
                                } else {
                                    getNewestHadith().insertEnglishText(hadithText);
                                }
                            } else if (hadithText.trim().matches("^Similar to no\\.? *[0-9]+ *\\.?$")) {
                                getNewestHadith().insertEnglishText(hadithText + " from this chapter.");
                            } else {
                                getNewestHadith().insertEnglishText(hadithText + " ");
                            }
                        } else {
                            if (!hadithObjects.isEmpty()) {
                                getNewestHadith().insertEnglishText(
                                        LoaderUtil.cleanupText(line.trim().replace("Translation:", "")) + " ");
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
        // Create english Hadith map
        hadithObjects.forEach(hadithObject -> {
            if (!hadithMap.containsKey(hadithObject.getChapter())) {
                hadithMap.put(hadithObject.getChapter(), new HashMap<>());
            }
            hadithMap.get(hadithObject.getChapter()).put(hadithObject.getNumber(), hadithObject);
        });
        // populate arabic hadith
        pdfLocation = "/ssd/onedrive/Desktop/KamilAlZiyarat.pdf";
        try {
            document = PDDocument.load(new File(pdfLocation));
        } catch (InvalidPasswordException e1) {
            e1.printStackTrace();
        } catch (IOException e1) {
            e1.printStackTrace();
        }
        reader = null;
        try {
            reader = new PDFTextStripper();
        } catch (IOException e1) {
            e1.printStackTrace();
        }

        chapter = "";
        String currHadithNo = "";
        String currChapterArabic = "";
        for (int i = 5; i < 246; i++) {
            writer.println("Processing Arabic page: " + i);
            try {
                reader.setStartPage(i);
                reader.setEndPage(i);
                String st = reader.getText(document);
                String[] lines = st.split("\n");
                for (int j = 0; j < lines.length; j++) {
                    String line = lines[j];
                    if (!line.trim().isEmpty() && !line.trim().matches("^[0-9]+.+:$")) {
                        if (line.toUpperCase().trim().startsWith("CHAPTER ")) {
                            chapter = line.trim();
                            currHadithNo = "0";
                            currChapterArabic = "";
                            while (!lines[j + 1].trim().matches("^.*\\-[0-9]+$")
                                    && !lines[j + 1].trim().matches("^ *\\-.*[0-9]+ *$")) {
                                currChapterArabic += " " + lines[j + 1].trim();
                                j++;
                            }
                            writer.println(chapter);
                            writer.flush();
                        } else if (!chapter.isEmpty()) {
                            if (line.trim().trim().matches("^.*\\-[0-9]+$")) {
                                String oldHadithNo = currHadithNo;
                                currHadithNo = line.substring(line.indexOf("-") + 1, line.length()).trim();
                                if (Integer.valueOf(currHadithNo) != (Integer.valueOf(oldHadithNo) + 1)) {
                                    writer.println("Hadith with chapter:" + chapter + " and number: " + currHadithNo
                                            + " is incorrectly number in Arabic pdf!");
                                    writer.flush();
                                    currHadithNo = String.valueOf(Integer.valueOf(oldHadithNo) + 1);
                                    writer.println("Setting the hadith number to " + currHadithNo + " instead");
                                }
                                addArabicText(chapter, currHadithNo, line.substring(0, line.indexOf("-")).trim() + " ",
                                        currChapterArabic);
                            } else if (line.trim().matches("^ *\\-.*[0-9]+ *$")) {
                                currHadithNo = LoaderUtil.numberFromEnd(line.trim());
                                addArabicText(chapter, currHadithNo,
                                        line.replace(currHadithNo, "").substring(line.indexOf("-") + 1).trim(),
                                        currChapterArabic);
                            } else if (!currHadithNo.isEmpty()) {
                                addArabicText(chapter, currHadithNo, line.trim() + " ", currChapterArabic);
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

        writer.close();
        hadithMap.values().forEach(chapter -> chapter.values().forEach(hadithObject -> {
            try {
                storeHadithToDb(hadithObject);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }));
    }

    private static String getHadithEnglishText(String chapter, String no) {
        for (HadithObject hadithObject : hadithObjects) {
            if (hadithObject.getChapter().equals(chapter) && hadithObject.getNumber().equals(no)) {
                return hadithObject.getEnglish();
            }
        }
        return null;
    }

    private static void addArabicText(String chapter, String number, String text, String arabicChapter)
            throws Exception {
        AtomicBoolean found = new AtomicBoolean(false);
        hadithMap.keySet().forEach(key -> {
            if (chapter.toUpperCase().equals(key.toUpperCase().split("-")[0].trim())) {
                HadithObject hadithObj = hadithMap.get(key).get(number);
                if (hadithObj != null) {
                    hadithObj.insertArabicText(text.trim());
                    if (!LoaderUtil.containsArabic(hadithObj.getChapter())) {
                        hadithObj.setChapter(hadithObj.getChapter() + " / " + arabicChapter.trim());
                    }
                    found.set(true);
                    writer.println("PROCESSED hadith with chapter: " + chapter + " and number: " + number);
                    writer.flush();
                }
            }
        });
        if (!found.get()) {
            writer.println("Hadith with chapter: " + chapter + " and number: " + number + " not found!");
            hadithMap.keySet().forEach(key -> {
                if (chapter.toUpperCase().equals(key.toUpperCase().split("-")[0].trim())) {
                    HadithObject hadithObj = new HadithObject();
                    hadithObj.setChapter(key + " / " + arabicChapter.trim());
                    hadithObj.setNumber(number);
                    hadithObj.setArabic(text);
                    hadithObj.setBook(book);
                    hadithMap.get(key).put(number, hadithObj);
                    writer.println("Inserted new hadith with chapter: " + chapter + " and number: " + number
                            + " with Arabic text only!");
                }
            });
        }
    }

    private static void storeHadithToDb(HadithObject completedHadith) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        if (completedHadith.getEnglish() != null && !completedHadith.getEnglish().isEmpty()) {
            completedHadith.setEnglish(LoaderUtil.cleanupText(completedHadith.getEnglish()));
        }
        if (completedHadith.getArabic() != null && !completedHadith.getArabic().isEmpty()) {
            completedHadith.setArabic(LoaderUtil.cleanupText(completedHadith.getArabic()));
        }
        String timeStamp = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(new java.util.Date());
        completedHadith.setHistory(Collections.singletonList("First loaded on " + timeStamp));
        byte[] json = mapper.writeValueAsBytes(completedHadith);
        boolean successful = false;
        int tries = 0;
        while (successful == false && tries < 8) {
            try {
                // ESClientProvider.instance().getClient().prepareIndex(ESClientProvider.INDEX,
                // "_doc")
                // .setSource(json).get();
                successful = true;
            } catch (NoNodeAvailableException e) {
                tries++;
                continue;
            }
        }
    }

    private static void setupNewHadithObj() {
        HadithObject currentHadith = new HadithObject();
        if (chapter != null) {
            currentHadith.setChapter(chapter);
        }
        currentHadith.setBook(book);
        hadithObjects.add(currentHadith);
    }

    private static HadithObject getOldestHadith() {
        return hadithObjects.get(0);
    }

    private static HadithObject getNewestHadith() {
        return hadithObjects.get(hadithObjects.size() - 1);
    }

    private static HadithObject completeOldestHadith() {
        HadithObject hadith = hadithObjects.get(0);
        hadith.setEnglish(LoaderUtil.cleanupText(hadith.getEnglish()));
        hadithObjects.remove(0);
        return hadith;
    }
}
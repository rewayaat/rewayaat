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

    private String chapter = "";
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
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        File myTempDir = Files.createTempDir();
        PDDocument document = null;
        String pdfLocation = "/home/zir0/git/rewayaatv2/rewayaat/src/main/java/com/rewayaat/loader/resources/kitab-al-tawheed-sadooq.pdf";
        try {
            document = PDDocument.load(new File(pdfLocation));
        } catch (InvalidPasswordException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        PDFTextStripper reader = null;
        try {
            reader = new PDFTextStripper();
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

        for (int i = start; i < end; i++) {
            writer.println("Processing page: " + i);
            try {
                reader.setStartPage(i);
                reader.setEndPage(i);
                String st = reader.getText(document);
                boolean containsArabic = false;
                String[] lines = st.split("\n");
                for (int j = 0; j < lines.length; j++) {
                    String line = lines[j];
                    System.out.println(line);
                    if (!LoaderUtil.isProbablyArabic(line)) {
                        if (!line.trim().isEmpty()) {
                            if (line.contains("Translator’s Note")
                                    || line.contains("Editor’s Note")
                                    || line.trim().matches("[0-9]+")) {

                                if (line.trim().matches("[0-9]+") && j == 0) {
                                    continue;
                                } else {
                                    // no more content we care about left on the page, go to next page.
                                    break;
                                }
                            } else if (line.toUpperCase().trim().startsWith("CHAPTER")) {

                                while (!lines[j + 1].toUpperCase().contains("CHAPTER") && !lines[j + 1].isEmpty()) {
                                    chapter += " " + lines[j + 1].trim();
                                    j++;
                                }
                            } else if (line.trim().matches("^[0-9]+\\..*$")) {
                                setupNewHadithObj();
                                getNewestHadith().setNumber(line.trim().substring(0, line.trim().indexOf("-")));
                                getNewestHadith().insertEnglishText(
                                        line.trim().substring(line.trim().indexOf("-") + 1).trim() + " ");
                            } else {
                                getNewestHadith().insertEnglishText(line.trim() + " ");
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
        myTempDir.delete();

    }

    public void saveHadith(PrintWriter writer, int newNumber) throws JsonProcessingException {

        ObjectMapper mapper = new ObjectMapper();
        HadithObject completedHadith = completeOldestHadith();
        while (Integer.parseInt(completedHadith.getNumber()) <= (newNumber - 1)) {
            byte[] json = mapper.writeValueAsBytes(completedHadith);
            boolean successful = false;
            int tries = 0;
            while (successful == false && tries < 8) {
                try {
                    if (completedHadith.getArabic() == null) {
                        writer.println("HADITH NUMBER " + completedHadith.getNumber() + " HAD NO ARABIC TEXT!");
                    }
                    ClientProvider.instance().getClient().prepareIndex(ClientProvider.INDEX, ClientProvider.TYPE)
                            .setSource(json).get();
                } catch (NoNodeAvailableException e) {
                    writer.println("No Node available Exception while processing current Hadith, will try AGAIN!:\n"
                            + getOldestHadith().toString() + "\n");
                    e.printStackTrace(writer);
                    tries++;
                    continue;
                }
                successful = true;
                hadithObjects.remove(0);
            }
            if (hadithObjects.isEmpty()) {
                break;
            }
            completedHadith = completeOldestHadith();
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

    /**
     * Cleans up the line for any know formatting issues.
     */
    public String cleanUpTheLine(String line) {
        return line.replaceAll("`’", "");
    }

    private HadithObject getOldestHadith() {
        return hadithObjects.get(0);
    }

    private HadithObject getNewestHadith() {
        if (hadithObjects.size() < 1) {
            System.out.println("");
        }
        return hadithObjects.get(hadithObjects.size() - 1);
    }

    private HadithObject completeOldestHadith() {
        HadithObject hadith = hadithObjects.get(0);
        hadith.setEnglish(cleanUpTheLine(hadith.getEnglish()));
        return hadith;
    }

}
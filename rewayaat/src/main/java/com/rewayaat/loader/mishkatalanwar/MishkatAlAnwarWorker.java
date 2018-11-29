package com.rewayaat.loader.mishkatalanwar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Files;
import com.rewayaat.config.ClientProvider;
import com.rewayaat.core.data.HadithObject;
import com.rewayaat.loader.LoaderUtil;
import com.rewayaat.loader.WordToNumber;
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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Must be executed on linux machine with the pdftopom package installed.
 */
public class MishkatAlAnwarWorker extends Thread {

    private String chapter = null;
    private String book = "Mishkat Al-Anwar Fi Ghurar il-Akhbar / مشكاة الأنوار في غرر الأخبار";
    private List<HadithObject> hadithObjects = new ArrayList<HadithObject>();
    private String section = null;
    private String part = null;
    private String volume = null;
    private int start;
    private int end;

    public MishkatAlAnwarWorker(int start, int end) {
        this.start = start;
        this.end = end;
    }

    String cleanupArabicNumber (String str) {
        return str.replaceAll("l", "1").replaceAll("L","1").replaceAll("I","1").replaceAll("O","0");
    }
    @Override
    public void run() {
        PrintWriter writer = null;
        try {
            writer = new PrintWriter((new BufferedWriter(new FileWriter(
                    "/home/zir0/git/rewayaatv2/rewayaat/src/main/java/com/rewayaat/loader/mishkatalanwar/operationLog_"
                            + start + "-" + end + ".txt",
                    true))));
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        File myTempDir = Files.createTempDir();
        PDDocument document = null;
        String pdfLocation = "/home/zir0/git/rewayaatv2/rewayaat/src/main/java/com/rewayaat/loader/resources/miskat.pdf";
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
                    if (!LoaderUtil.isProbablyArabic(line) && !line.contains("األنوار في")) {

                        if (line.contains("PDF created with")
                                || line.trim()
                                        .equals("Akhbar")
                                || (line.trim()
                                        .matches(
                                                "^[0-9]+.*Mishkat ul-Anwar fi.*$"))
                                || line.trim().isEmpty()
                                || (section != null
                                        && section.toUpperCase().trim().replaceAll(" ,-", "")
                                                .contains(line.toUpperCase().trim().replaceAll(" ,.-", ""))
                                        && !line.isEmpty())) {
                            continue;
                        } else if (line.contains("Translators' note:")) {
                            break;
                        } else if (line.toUpperCase().trim().matches("^SECTION[\\s\\xA0][A-Z]+$")) {
                            section = "SECTION "
                                    + WordToNumber.wordToNumber(line.split(" ")[line.split(" ").length - 1]) + " -";
                            while (!lines[j + 1].toUpperCase().contains("CHAPTER") && !lines[j + 1].isEmpty()) {
                                section += " " + lines[j + 1].trim();
                                j++;
                            }
                        } else if (line.toUpperCase().trim().matches("^CHAPTER[\\s\\xA0][0-9]+$")) {
                            chapter = line.trim().toUpperCase() + " -";
                            while (!lines[j + 1].matches("^[0-9]+-.*$") && !lines[j + 1].isEmpty()
                                    && !lines[j + 1].trim().startsWith("God the Almighty")) {
                                chapter += " " + lines[j + 1].trim();
                                j++;
                            }
                        } else if (line.trim().matches("^[0-9]+-.*$")) {
                            setupNewHadithObj();
                            getNewestHadith().setNumber(line.trim().substring(0, line.trim().indexOf("-")));
                            getNewestHadith().insertEnglishText(
                                    line.trim().substring(line.trim().indexOf("-") + 1).trim() + " ");
                        } else {
                            if (line.contains("Translators")) {
                                line = line.substring(0, line.indexOf("Translators")).trim();
                            }
                            getNewestHadith().insertEnglishText(line.trim() + " ");
                        }
                    } else {
                        containsArabic = true;
                    }
                }

                if (containsArabic) {
                    Process p;
                    try {
                        p = Runtime.getRuntime().exec("sudo pdftoppm -f " + i + " -l " + i + " -r 300 -png "
                                + pdfLocation + " ocr" + i + ".0", null, myTempDir);
                        p.waitFor();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    String ocrText = LoaderUtil.sendOCRAPIPost(LoaderUtil.getLatestFilefromDir(myTempDir.getAbsolutePath()));
                    String[] ocrLines = ocrText.split("\r\n");

                    for (int j = 0; j < ocrLines.length; j++) {
                        String ocrLine = ocrLines[j];
                        System.out.println(ocrLine);
                        String matchingArabicText = LoaderUtil.matchingArabicText(ocrLine, new ArrayList(Arrays.asList(lines)));
                        String finalArabicString = LoaderUtil.combineArabicStrings(ocrLine, matchingArabicText);
                        if (LoaderUtil.isProbablyArabic(ocrLine)) {
                            ocrLine = cleanupArabicNumber(ocrLine);
                            if (ocrLine.contains("PDF created with") || ocrLine.contains("ععـتعلعقللفعلا")
                                    || ocrLine.trim().isEmpty() || ocrLine.trim().contains("مشكاة الأنوار في")
                                    || ocrLine.trim().equals("غرر الأخبار") || ocrLine.trim().equals("الأخبار")
                                    || (section != null && chapter.trim().toUpperCase().replaceAll(" ,-", "")
                                    .contains(ocrLine.trim().toUpperCase().replaceAll(" ,-", "")))
                                    && !ocrLine.isEmpty()) {
                                continue;
                            } else if (ocrLine.trim().contains("الباب") && j == 0) {
                                section += " / " + ocrLine.trim();
                                while (!cleanupArabicNumber(ocrLines[j + 1]).contains("الفصل") && !ocrLines[j + 1].isEmpty()) {
                                    section += " " + cleanupArabicNumber(ocrLines[j + 1]).trim();
                                    j++;
                                }
                                for (HadithObject hadith : hadithObjects) {
                                    hadith.setSection(section);
                                }
                            } else if (ocrLine.trim().contains("الفصل")) {
                                chapter += " / ";
                                while (!cleanupArabicNumber(ocrLines[j + 1]).matches("^[\\s\\xA0]?[0-9]+[\\s\\xA0]*\\..*$")
                                        && !ocrLines[j + 1].isEmpty()) {
                                    chapter += " " + ocrLines[j + 1].trim();
                                    j++;
                                }
                                for (HadithObject hadith : hadithObjects) {
                                    hadith.setChapter(chapter);
                                }
                            } else {
                                Pattern arabicHadithStartPattern = Pattern.compile("(^ *(?<number>[0-9]+) *(\\.).*)$|(^.*(\\.) *(?<number2>[0-9]+) *$)");
                                Matcher m = arabicHadithStartPattern.matcher(ocrLine.trim());
                                int number = 0;
                                if (m.find()) {
                                    if (m.group(1) != null) {
                                        number = Integer.parseInt(m.group("number").trim());
                                    } else if (m.group(4) != null) {
                                        number = Integer.parseInt(m.group("number2").trim());
                                    }
                                }
                                if ((!ocrLine.trim().startsWith("1.")
                                        && number != 0)
                                        || ocrLine.toUpperCase().trim().equals("THE MAIN REFERENCES")) {

                                    if (!hadithObjects.isEmpty()) {
                                        writer.println("COMPLETING HADITH NUMBER:" + getOldestHadith().getNumber());
                                        saveHadith(writer, number);
                                    }
                                    writer.println("ADDING LINE TO HADITH NUMBER :" + getOldestHadith().getNumber() + "\n"
                                            + finalArabicString.replace(String.valueOf(number), "").replace(".","").trim());
                                    getOldestHadith().insertArabicText(cleanupArabicNumber(finalArabicString.replace(String.valueOf(number), ""))
                                            .replace(".","").replaceAll(":", "")
                                            .replaceAll("0","").replaceAll( " م " , " عليه السلام ").replaceAll( " 4 " , " عليه السلام ").trim() + " ");
                                } else {
                                    writer.println("ADDING LINE TO HADITH NUMBER :" + getOldestHadith().getNumber() + "\n"
                                            + finalArabicString.trim());
                                    getOldestHadith().insertArabicText(finalArabicString.trim() + " ");
                                }
                            }
                        }
                    }
                    File fileToDelete = LoaderUtil.getLatestFilefromDir(myTempDir.getAbsolutePath());
                    fileToDelete.delete();
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
            boolean successfull = false;
            int tries = 0;
            while (successfull == false && tries < 8) {
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
                successfull = true;
                hadithObjects.remove(0);
            }
            if (hadithObjects.isEmpty()) {
                break;
            }
            completedHadith = completeOldestHadith();
            successfull = false;
            tries = 0;
        }
    }


    public void setupNewHadithObj() {
        HadithObject currentHadith = new HadithObject();
        if (volume != null) {
            currentHadith.setVolume(volume);
        }
        if (part != null) {
            currentHadith.setPart(part);
        }
        if (chapter != null) {
            currentHadith.setChapter(chapter);
        }
        if (section != null) {
            currentHadith.setSection(section);
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
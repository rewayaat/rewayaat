package com.rewayaat.loader;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.UnknownHostException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.text.PDFTextStripper;
import org.json.JSONObject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Files;
import com.rewayaat.web.config.ClientProvider;
import com.rewayaat.web.data.hadith.HadithObject;

/**
 * Loads Arabic part of hadith into the system for narrations from Al-Kafi
 */
public class AlKafiLoaderArabic {

    public static String chapter;
    public static String currentPart = "";
    public static HadithObject currentHadith = new HadithObject();
    public static String book = "Al-Kafi";
    public static String section;
    public static String part;
    public static String volume;

    public static void main(String[] args) throws Exception {

        File myTempDir = Files.createTempDir();
        BufferedWriter out = new BufferedWriter(
                new FileWriter("/root/git/rewayaat/rewayaat/src/main/java/com/rewayaat/loader/resources/file.txt"));
        PDDocument document = null;
        document = PDDocument
                .load(new File("/root/git/rewayaat/rewayaat/src/main/java/com/rewayaat/loader/resources/alkafi.pdf"));
        PDFTextStripper reader = new PDFTextStripper();
        PDPageTree pages = document.getPages();

        for (int i = 1; i < pages.getCount(); i++) {

            reader.setStartPage(i);
            reader.setEndPage(i);
            String st = reader.getText(document);

            // ignore table of contents page
            if (st.toLowerCase().contains("table of contents") || (st.contains("Chapter") && st.contains("..."))) {
                continue;
            }

            Process p;
            try {
                p = Runtime.getRuntime()
                        .exec("sudo pdftoppm -f " + i + " -l " + i
                                + " -r 300 -png /root/git/rewayaat/rewayaat/src/main/java/com/rewayaat/loader/resources/alkafi.pdf ocr"
                                + i + ".0", null, myTempDir);
                p.waitFor();
            } catch (Exception e) {
                e.printStackTrace();
            }

            st = sendOCRAPIPost(getLatestFilefromDir(myTempDir.getAbsolutePath()));

            // get rid of the following suffixes
            st = st.replaceAll("azwj", "").replaceAll("asws", "").replaceAll("saww", "")
                    .replaceAll("satanla", "satan(la)").replaceAll("yazidla", "yazid(la)");

            String[] lines = st.split("\n");
            for (int j = 0; j < lines.length; j++) {
                String line = lines[j];
                if (line.toUpperCase().trim().matches("^VOIUME[\\s\\xA0][0-9]+$")) {
                    volume = line.substring(line.indexOf("VoIume") + 7).trim();
                    out.write("VOLUME " + volume + "\n");
                    if (Integer.parseInt(volume) < 3) {
                        part = "Al-Usul (Principles)";
                    } else if (Integer.parseInt(volume) == 8) {
                        part = "Al-Rawda' (Miscellanea)";
                    } else {
                        part = "Al-Furu' (Jurisprudence)";
                    }
                    out.write("PART " + part + "\n");
                } else if (line.toUpperCase().trim().startsWith("THE BOOK OF") && line.trim().endsWith(")")) {
                    int v = j;
                    section = "";
                    while (!lines[v].matches("[\\s\\xA0]*") && !lines[v]
                            .matches("^[\\s\\xA0]*[0-9]+[\\s\\xA0]out[\\s\\xA0]of[\\s\\xA0][0-9]+[\\s\\xA0]*$")) {
                        section += lines[v].trim() + " ";
                        v++;
                    }
                    section = cleanUpTheLine(section);
                    out.write("SECTION " + section + "\n");
                    j = v;
                } else if (line.startsWith("Chapter ") && !line.contains(".")) {
                    lines[j] = line.trim().substring(line.indexOf("Chapter") + 9).trim();
                    int y = j;
                    chapter = "";
                    while (!isProbablyArabic(lines[y])) {
                        chapter += lines[y].trim() + " ";
                        chapter = cleanUpTheLine(chapter);
                        y++;
                    }
                    out.write("CHAPTER " + chapter + "\n");
                    j = y;
                    setupNewHadithObj();

                } else if (line.contains("hubeali.com") || line.contains("kafi VoIume ")
                        || line.contains("Alkafi Volume ") || line.contains("Al Kafi V") || line.contains("AI Kafi V")
                        || line.contains("The Book Of")
                        || line.matches(".*CH[\\s\\xA0][0-9]+[\\s\\xA0]H[\\s\\xA0][0-9]+.*")
                        || line.matches("^[\\s\\xA0]*[0-9]+[\\s\\xA0]out[\\s\\xA0]of[\\s\\xA0][0-9]+[\\s\\xA0]*$")
                        || line.matches("^[0-9]+[()]*[\\s\\xA0]*$") || line.contains("hubeali.com")
                        || line.matches("[\\s\\xA0]*")) {
                    continue;

                } else if (!isProbablyArabic(line)) {
                    // start entering hadith only if we have a valid volume,
                    // chapter, section
                    if (book != null && chapter != null && volume != null && section != null && line != null) {

                        if (line.matches(".*[\\s\\xA0]*[0-9]+\\.?[\\s\\xA0]*$")) {
                            currentHadith.setNumber(line.substring(line.lastIndexOf(".") + 1).trim());
                            saveHadith();
                            setupNewHadithObj();
                        }
                    }
                } else if (isProbablyArabic(line)) {
                    currentHadith.insertArabicText(cleanUpTheLine(line) + " ");
                }
            }
        }

        myTempDir.delete();

    }

    private static File getLatestFilefromDir(String dirPath) {
        File dir = new File(dirPath);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }

        File lastModifiedFile = files[0];
        for (int i = 1; i < files.length; i++) {
            if (lastModifiedFile.lastModified() < files[i].lastModified()) {
                lastModifiedFile = files[i];
            }
        }
        return lastModifiedFile;
    }

    public static void saveHadith() throws JsonProcessingException, UnknownHostException {
        ObjectMapper mapper = new ObjectMapper();
        byte[] json = mapper.writeValueAsBytes(currentHadith);
        ClientProvider.instance().getClient().prepareIndex(ClientProvider.INDEX, ClientProvider.TYPE).setSource(json)
                .get();
    }

    private static String sendOCRAPIPost(File file) throws IOException, Exception {

        HttpPost httppost = new HttpPost("https://api.ocr.space/parse/image");

        byte[] imageBytes = IOUtils.toByteArray(new FileInputStream(file));
        String encodedfile = new String(org.apache.commons.codec.binary.Base64.encodeBase64(imageBytes), "UTF-8");

        HttpEntity entity = MultipartEntityBuilder.create().setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                .addTextBody("base64image", "data:image/png;base64," + encodedfile)
                .addTextBody("apikey", "6cf60415ef88957").addTextBody("isOverlayRequired", "false")
                .addTextBody("language", "ara").build();

        httppost.setEntity(entity);
        HttpClient httpClient = HttpClientBuilder.create().build();
        org.apache.http.HttpResponse response = httpClient.execute(httppost);

        String json_string = EntityUtils.toString(response.getEntity());
        return new JSONObject(json_string).getJSONArray("ParsedResults").getJSONObject(0).getString("ParsedText");

    }

    public static void setupNewHadithObj() {
        currentHadith = new HadithObject();
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
    }

    /**
     * Cleans up the line for any know formatting issues.
     */
    public static String cleanUpTheLine(String line) {

        // reverse the words to make valid rtl text
        return StringUtils.reverseDelimited(line, ' ');
    }

    /**
     * Returns true if there more than half of the characters in the given
     * string are arabic letters.
     */
    public static boolean isProbablyArabic(String s) {
        int sLen = s.length();
        int hits = 0;
        for (int i = 0; i < s.length();) {
            int c = s.codePointAt(i);
            if (c >= 0x0600 && c <= 0x06E0)
                hits++;
            i += Character.charCount(c);
        }
        if ((sLen / 2) > hits) {
            return false;
        } else {
            return true;
        }
    }
}
package com.rewayaat.loader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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
 * Loads english part of hadith into the system for narrations from Al-Kafi
 */
public class AlKafiLoaderEnglish {

    public static String chapter;
    public static String currentPart = "";
    public static HadithObject currentHadith = new HadithObject();
    public static String book = "Al-Kafi";
    public static String section;
    public static String part;
    public static String volume;

    public static void main(String[] args) throws Exception {

        File myTempDir = Files.createTempDir();
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

            String ocrText = sendOCRAPIPost(getLatestFilefromDir(myTempDir.getAbsolutePath()));
            List<String> arabicChunks = splitOCRTextIntoArabicChunks(ocrText, i);

            // get rid of the following suffixes
            st = st.replaceAll("azwj", "").replaceAll("asws", "").replaceAll("saww", "")
                    .replaceAll("satanla", "satan(la)").replaceAll("yazidla", "yazid(la)");

            String[] lines = st.split("\n");
            for (int j = 0; j < lines.length; j++) {
                String line = lines[j];
                if (line.contains("BOOK OF")) {
                    System.out.println();
                }
                if (line.toUpperCase().trim().matches("^VOLUME[\\s\\xA0][0-9]+$")) {
                    volume = line.substring(line.indexOf("Volume") + 7).trim();
                    if (Integer.parseInt(volume) < 3) {
                        part = "Al-Usul (Principles)";
                    } else if (Integer.parseInt(volume) == 8) {
                        part = "Al-Rawda' (Miscellanea)";
                    } else {
                        part = "Al-Furu' (Jurisprudence)";
                    }
                } else if (line.toUpperCase().trim().startsWith("THE BOOK OF")) {
                    int v = j;
                    section = "";
                    while (!lines[v].matches("[\\s\\xA0]*") && !lines[v]
                            .matches("^[\\s\\xA0]*[0-9]+[\\s\\xA0]out[\\s\\xA0]of[\\s\\xA0][0-9]+[\\s\\xA0]*$")) {
                        section += lines[v].trim() + " ";
                        v++;
                    }
                    if (section.contains("(") && section.contains(")")) {
                        section = section.substring(0, section.indexOf("("));
                    }
                    section = cleanUpTheLine(section);
                    j = v - 1;
                } else if (line.toUpperCase().matches("CHAPTER[\\s\\xA0]*[0-9]+[\\s\\xA0]–.*") && !line.contains(".")) {
                    lines[j] = line.trim().substring(line.indexOf("CHAPTER") + 9).trim();
                    int y = j;
                    chapter = "";
                    while (!isProbablyArabic(lines[y])) {
                        chapter += lines[y].trim() + " ";
                        chapter = cleanUpTheLine(chapter);
                        y++;
                    }
                    j = y - 1;
                    setupNewHadithObj();

                } else if (line.contains("hubeali.com") || line.startsWith("Alkafi Volume ")
                        || line.contains("Al Kafi V") || line.contains("The Book Of")
                        || line.matches(".*CH[\\s\\xA0][0-9]+[\\s\\xA0]H[\\s\\xA0][0-9]+.*")
                        || line.matches("^[\\s\\xA0]*[0-9]+[\\s\\xA0]out[\\s\\xA0]of[\\s\\xA0][0-9]+[\\s\\xA0]*$")
                        || line.matches("^[0-9]+[()]*[\\s\\xA0]*$") || line.contains("hubeali.com")
                        || line.matches("[\\s\\xA0]*")) {
                    continue;

                } else if (!isProbablyArabic(line)) {
                    // start entering hadith only if we have a valid volume,
                    // chapter, section
                    if (book != null && chapter != null && volume != null && section != null && line != null) {
                        line = cleanUpTheLine(line);

                        if (line.matches(".*\\.[\\s\\xA0]*[0-9]+[\\s\\xA0]*$")) {
                            currentHadith.setNumber(line.substring(line.lastIndexOf(".") + 1).trim());
                            line = line.substring(0, line.lastIndexOf(".") + 1);
                            currentHadith.insertEnglishText(line.trim() + " ");
                            saveHadith();
                            setupNewHadithObj();
                        } else {
                            currentHadith.insertEnglishText(line.trim() + " ");
                        }
                    }
                } else if (isProbablyArabic(line)) {
                    int s = j;
                    String arabicText = "";
                    while (s <= lines.length - 1 && isProbablyArabic(lines[s])) {
                        arabicText += lines[s].trim() + " ";
                        s++;
                    }
                    j = s - 1;

                    // compare our Arabic Text to Arabic chunks to find the
                    // correct replacement chunk
                    for (String arabicChuck : arabicChunks) {
                        if (matchingArabicText(arabicText, arabicChuck)) {
                            currentHadith.insertArabicText(combineArabicStrings(arabicChuck, arabicText));
                        }
                    }
                }
            }
        }
        myTempDir.delete();
    }

    private static boolean matchingArabicText(String diacraticString, String normalizedArabicString) {

        String normalizedDiacraticString = new ArabicNormalizer(diacraticString).getOutput();

        int normalizedDiacraticStringLenWithNoSpaces = normalizedDiacraticString.replaceAll("[\\s\\xA0]", "").length();
        int normalizedArabicStringLenWithNoSpaces = normalizedArabicString.replaceAll("[\\s\\xA0]", "").length();

        // quick check
        if (Math.abs(normalizedDiacraticStringLenWithNoSpaces
                - normalizedArabicStringLenWithNoSpaces) > (((normalizedDiacraticStringLenWithNoSpaces
                        + normalizedArabicStringLenWithNoSpaces) / 2) * 0.2)) {
            return false;
        } else {

            String[] dicraticWords = normalizedDiacraticString.split("[\\s\\xA0]");
            for (String dicraticWord : dicraticWords) {
                normalizedArabicString = normalizedArabicString.replaceAll(Pattern.quote(dicraticWord.trim()), "");
            }
            // if the two strings are similar, we would expect that the final
            // normalizedArabicString is very small.
            if (normalizedArabicString.replaceAll("[\\s\\xA0].[\\s\\xA0]", "").replaceAll("[\\s\\xA0]", "")
                    .length() < (normalizedArabicStringLenWithNoSpaces * 0.4)) {
                return true;
            } else {
                return false;
            }
        }

    }

    private static String combineArabicStrings(String normalizedArabic, String diacraticArabic) {

        Set<String> diacraticWords = new HashSet<String>(Arrays.asList(diacraticArabic.split("[\\s\\xA0]")));
        for (String dicraticWord : diacraticWords) {
            String normalizedDiacraticWord = new ArabicNormalizer(dicraticWord).getOutput();
            if (normalizedArabic.contains(normalizedDiacraticWord) && !normalizedDiacraticWord.isEmpty()
                    && normalizedDiacraticWord.length() >= 3) {
                normalizedArabic = normalizedArabic.replaceAll(Pattern.quote(normalizedDiacraticWord), dicraticWord);
            }
        }
        return normalizedArabic;
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

    private static List<String> splitOCRTextIntoArabicChunks(String ocrText, int page) {
        List<String> arabicChunks = new ArrayList<String>();
        arabicChunks.add("");
        String[] lines = ocrText.split("\n");
        for (String line : lines) {
            if (isProbablyArabic(line)) {
                arabicChunks.set(arabicChunks.size() - 1, (arabicChunks.get(arabicChunks.size() - 1) + " "
                        + StringUtils.reverseDelimited(line, ' ').trim()));
            } else {
                if (!arabicChunks.get(arabicChunks.size() - 1).isEmpty()) {
                    arabicChunks.add("");
                }
            }
        }
        if (arabicChunks.get(arabicChunks.size() - 1).isEmpty()) {
            arabicChunks.remove(arabicChunks.size() - 1);
        }
        System.out.println("Arabic chunks size is " + arabicChunks.size() + " for page " + page);
        return arabicChunks;
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

        String newStr = "";
        String[] words = line.split("[\\s\\xA0]");
        for (String word : words) {
            if (replaceAsAtEndWords.contains(word.toLowerCase().replaceAll("[(),.-?’]", ""))) {
                for (String replaceWord : replaceAsAtEndWords) {
                    if (replaceWord.equals(word.toLowerCase().replaceAll("[(),-?’]", ""))) {
                        word = word.toLowerCase().replaceAll(replaceWord,
                                replaceWord.substring(0, replaceWord.length() - 2));
                        // capitalize first letter
                        word = word.substring(0, 1).toUpperCase() + word.substring(1);
                        break;
                    }
                }
            }
            newStr += word + " ";
        }
        return newStr;
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

    private static final List<String> replaceAsAtEndWords = Arrays.asList(new String[] { "prophetsas", "prophetas",
            "rasoolsas", "adamas", "theyas", "theiras", "heas", "ibrahimas", "yaqoubas", "yunusas", "musaas",
            "brotheras", "talibas", "farwaas", "zakariyaas", "ayyubas", "shuaibas", "hisas", "ishaqas", "ismailas",
            "ibrahimas", "lutas", "salihas", "hudas", "idrisas", "mursilsas", "yahyaas", "qasimas", "sheas", "youras",
            "himas", "khadeejaas", "jibraeelas", "isaas", "nuhas", "maryamas", "uttalibas", "rasoolas", "successorsas",
            "heas", "sheas", "sonas", "yusufas", "youas", "meas", "ias", "hamzaas" });

}
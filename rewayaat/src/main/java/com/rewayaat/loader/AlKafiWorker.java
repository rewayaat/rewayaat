package com.rewayaat.loader;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
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
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.json.JSONObject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Files;
import com.rewayaat.web.config.ClientProvider;
import com.rewayaat.web.data.hadith.HadithObject;

/**
 * Must be executed on linux machine with the pdftopom package installed.
 */
public class AlKafiWorker extends Thread {

    private String chapter = null;
    private HadithObject currentHadith = new HadithObject();
    private String book = "Al-Kafi / الكافي";
    private String section = null;
    private String part = null;
    private String volume = null;
    private int start;
    private final List<String> replaceAsAtEndWords = Arrays.asList(new String[] { "prophetsas", "prophetas",
            "rasoolsas", "adamas", "theyas", "theiras", "heas", "ibrahimas", "yaqoubas", "yunusas", "musaas",
            "brotheras", "talibas", "farwaas", "zakariyaas", "ayyubas", "shuaibas", "hisas", "ishaqas", "ismailas",
            "ibrahimas", "lutas", "salihas", "hudas", "idrisas", "mursilsas", "yahyaas", "qasimas", "sheas", "youras",
            "himas", "khadeejaas", "jibraeelas", "isaas", "nuhas", "maryamas", "uttalibas", "rasoolas", "successorsas",
            "heas", "sheas", "sonas", "yusufas", "youas", "meas", "ias", "hamzaas" });

    private int end;

    public AlKafiWorker(int start, int end) {
        this.start = start;
        this.end = end;
    }

    @Override
    public void run() {
        PrintWriter writer = null;
        try {
            writer = new PrintWriter((new BufferedWriter(new FileWriter(
                    "/home/zir0/git/rewayaatv2/rewayaat/src/main/java/com/rewayaat/loader/resources/operationLog_"
                            + start + "-" + end + ".txt",
                    true))));
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        File myTempDir = Files.createTempDir();
        PDDocument document = null;
        String pdfLocation = "/home/zir0/git/rewayaatv2/rewayaat/src/main/java/com/rewayaat/loader/resources/alkafi.pdf";
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

                // ignore table of contents page
                if (st.toLowerCase().contains("table of contents") || (st.contains("Chapter") && st.contains("..."))) {
                    continue;
                }

                Process p;
                try {
                    p = Runtime.getRuntime().exec(
                            "sudo pdftoppm -f " + i + " -l " + i + " -r 300 -png " + pdfLocation + " ocr" + i + ".0",
                            null, myTempDir);
                    p.waitFor();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                String ocrText = sendOCRAPIPost(getLatestFilefromDir(myTempDir.getAbsolutePath()));
                List<String> arabicChunks = splitOCRTextIntoArabicChunks(ocrText, i);

                // get rid of the following suffixes
                st = st.replaceAll("azwj", "").replaceAll("asws", "").replaceAll("saww", "")
                        .replaceAll("satanla", "satan(la)").replaceAll("yazidla", "yazid(la)")
                        .replaceAll("Yazidla", "Tazid(la)").replaceAll("Yazeedla", "Yazeed(la)")
                        .replaceAll("Satanla", "Satan(la)").replaceAll("Satanlsa", "Satans(la)");

                String[] lines = st.split("\n");
                for (int j = 0; j < lines.length; j++) {
                    String line = lines[j];
                    if (line.toUpperCase().trim().matches("^VOLUME[\\s\\xA0][0-9]+$")) {
                        volume = line.substring(line.indexOf("Volume") + 7).trim();
                        if (Integer.parseInt(volume) < 3) {
                            part = "Al-Usul (Principles) / أصول الكافي";
                        } else if (Integer.parseInt(volume) == 8) {
                            // can't process volume 8 yet.
                            break;
                            // part = "Al-Rawda' (Miscellanea)";
                        } else {
                            part = "Al-Furu' (Jurisprudence) / فـروع الـكـافـي";
                        }
                    } else if (line.trim().startsWith("THE BOOK OF")) {
                        int v = j;
                        section = "";
                        while (!lines[v].matches("[\\s\\xA0]*") && !lines[v]
                                .matches("^[\\s\\xA0]*[0-9]+[\\s\\xA0]out[\\s\\xA0]of[\\s\\xA0][0-9]+[\\s\\xA0]*$")) {
                            section += lines[v].trim() + " ";
                            v++;
                        }
                        if (section.contains("(") && section.contains(")")) {
                            section = section.substring(0, section.lastIndexOf("(")).trim();
                        }
                        section = cleanUpTheLine(section);

                        // get section in arabic
                        if (section.length() > 2) {
                            String arabicText = getPreceedingArabicText(lines, j);
                            if (!arabicText.trim().isEmpty() && !(new ArabicNormalizer(arabicText).getOutput()
                                    .length() > (section.length() * 3))) {
                                arabicText = combineArabicStrings(matchingArabicText(arabicText, arabicChunks),
                                        arabicText);
                                section += " / " + arabicText;
                            }
                        }
                        j = v - 1;
                    } else if ((line.toUpperCase().matches(
                            "(CHAPTER|CHAPATER|CHHAPTER|CHAPER|CHATER|CHAPAATER|CHAPPTER|CAHPTER)[\\s\\xA0]*[0-9]+[\\s\\xA0]–.*")
                            && !line.contains(".") && !st.contains("...."))
                            || line.toUpperCase().trim().contains("Chapter 13 The Imams are the Light")
                            || (line.trim().equals("Chapter 1") && i == 627)) {

                        int y = j;
                        chapter = "";
                        while (!isProbablyArabic(lines[y]) && !lines[y].matches("[\\s\\xA0]*")) {
                            chapter += lines[y].trim() + " ";
                            chapter = cleanUpTheLine(chapter);
                            y++;
                        }
                        String[] chapterTitleWords = chapter.trim().split("[\\s\\xA0]");
                        chapterTitleWords[0] = "Chapter";
                        chapter = String.join(" ", chapterTitleWords);

                        // get chapter name in arabic
                        if (chapter.length() > 2) {
                            String arabicText = getPreceedingArabicText(lines, j);
                            if (!arabicText.trim().isEmpty() && !(new ArabicNormalizer(arabicText).getOutput()
                                    .length() > (chapter.length() * 3))) {
                                arabicText = combineArabicStrings(matchingArabicText(arabicText, arabicChunks),
                                        arabicText);
                                chapter += " / " + arabicText;
                            }
                        }
                        j = y - 1;
                        setupNewHadithObj();

                    } else if (line.contains("hubeali.com") || line.startsWith("Alkafi Volume ")
                            || line.contains("Al Kafi V") || line.contains("The Book Of")
                            || line.contains("Al Kafi – V") || line.contains("Al Kafi - V")
                            || line.contains("Al-Kafi – V") || line.contains("Al-Kafi - V")
                            || line.matches(".*CH[\\s\\xA0][0-9]+[\\s\\xA0]H[\\s\\xA0][0-9]+.*")
                            || line.matches("^[\\s\\xA0]*[0-9]+[\\s\\xA0]out[\\s\\xA0]of[\\s\\xA0][0-9]+[\\s\\xA0]*$")
                            || line.matches("^[0-9]+[()]*[\\s\\xA0]*$") || line.contains("hubeali.com")
                            || line.matches("[\\s\\xA0]*")) {
                        continue;

                    } else if (!isProbablyArabic(line)) {
                        // start entering hadith only if we have a valid
                        // volume,
                        // chapter, section
                        if (book != null && chapter != null && volume != null && section != null && line != null) {
                            line = cleanUpTheLine(line);

                            if (line.matches(".*(\\.|\\?|!|'|-|`|~|’)[\\s\\xA0]*[0-9]+[\\s\\xA0]*$")) {
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

                        // compare our Arabic Text to Arabic chunks to find
                        // the
                        // correct replacement chunk
                        String matchingArabicText = matchingArabicText(arabicText, arabicChunks);
                        currentHadith.insertArabicText(combineArabicStrings(matchingArabicText, arabicText));
                    }
                }
            } catch (Exception e) {
                writer.println("Error while processing current Hadith:\n" + currentHadith.toString() + "\n");
                e.printStackTrace(writer);
                continue;
            }
            writer.println("Finished Processing page: " + i);
            writer.flush();
        }
        writer.close();
        myTempDir.delete();
    }

    public String getPreceedingArabicText(String[] lines, int boundaryLine) {

        String preceedingArabicText = "";
        boolean foundArabic = false;
        boolean done = false;
        for (int f = boundaryLine - 1; f > 0 && !done && f > boundaryLine - 5; f--) {
            if (isProbablyArabic(lines[f])) {
                foundArabic = true;
                preceedingArabicText += lines[f].trim() + " ";
            } else if (foundArabic && !isProbablyArabic(lines[f])) {
                done = true;
            }
        }
        return preceedingArabicText;
    }

    private String matchingArabicText(String diacraticString, List<String> arabicChunks) {

        // figures out matching Arabic text by using String length and word
        // similarity
        String currArabicChunkLeaderStr = "";
        int currArabicChunkLeaderScore = -1;

        for (String candidateArabicChunk : arabicChunks) {

            if (candidateArabicChunk.matches("[\\s\\xA0]*")) {
                continue;
            }

            String normalizedDiacraticString = new ArabicNormalizer(diacraticString).getOutput();
            int normalizedDiacraticStringLenWithNoSpaces = normalizedDiacraticString.replaceAll("[\\s\\xA0]", "")
                    .length();
            int normalizedArabicStringLenWithNoSpaces = candidateArabicChunk.replaceAll("[\\s\\xA0]", "").length();
            int score = Math.abs(normalizedDiacraticStringLenWithNoSpaces - normalizedArabicStringLenWithNoSpaces);
            String[] dicraticWords = normalizedDiacraticString.split("[\\s\\xA0]");
            String candidateArabicChunkCopy = candidateArabicChunk;
            for (String dicraticWord : dicraticWords) {
                candidateArabicChunkCopy = candidateArabicChunkCopy.replaceAll(Pattern.quote(dicraticWord.trim()), "");
            }
            // if the two strings are similar, we would expect that the
            // final normalizedArabicString is very small.
            score += candidateArabicChunkCopy.replaceAll("[\\s\\xA0].[\\s\\xA0]", "").replaceAll("[\\s\\xA0]", "")
                    .length();

            if (currArabicChunkLeaderScore == -1 || score < currArabicChunkLeaderScore) {
                currArabicChunkLeaderScore = score;
                currArabicChunkLeaderStr = candidateArabicChunk;
            }
        }
        return currArabicChunkLeaderStr;
    }

    private String combineArabicStrings(String normalizedArabic, String diacraticArabic) {

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

    private File getLatestFilefromDir(String dirPath) {
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

    private List<String> splitOCRTextIntoArabicChunks(String ocrText, int page) {
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

    public void saveHadith() throws JsonProcessingException, UnknownHostException {
        ObjectMapper mapper = new ObjectMapper();
        byte[] json = mapper.writeValueAsBytes(currentHadith);
        ClientProvider.instance().getClient().prepareIndex(ClientProvider.INDEX, ClientProvider.TYPE).setSource(json)
                .get();
    }

    private String sendOCRAPIPost(File file) throws IOException, Exception {

        HttpPost httppost = new HttpPost("https://api.ocr.space/parse/image");

        byte[] imageBytes = IOUtils.toByteArray(new FileInputStream(file));
        String encodedfile = new String(org.apache.commons.codec.binary.Base64.encodeBase64(imageBytes), "UTF-8");

        HttpEntity entity = MultipartEntityBuilder.create().setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                .addTextBody("base64image", "data:image/png;base64," + encodedfile)
                .addTextBody("apikey", "PKMXB3676888A").addTextBody("isOverlayRequired", "false")
                .addTextBody("language", "ara").build();

        httppost.setEntity(entity);
        HttpClient httpClient = HttpClientBuilder.create().build();
        org.apache.http.HttpResponse response = httpClient.execute(httppost);

        String json_string = EntityUtils.toString(response.getEntity());
        return new JSONObject(json_string).getJSONArray("ParsedResults").getJSONObject(0).getString("ParsedText");

    }

    public void setupNewHadithObj() {
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
    public String cleanUpTheLine(String line) {

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
    public boolean isProbablyArabic(String s) {
        if (s.matches("[\\s\\xA0]*")) {
            return false;
        }
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
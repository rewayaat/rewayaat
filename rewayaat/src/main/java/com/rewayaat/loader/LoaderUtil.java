package com.rewayaat.loader;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class LoaderUtil {

    public static String combineArabicStrings(String normalizedArabic, String diacraticArabic) {
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

    public static String matchingArabicText(String normalizedString, List<String> arabicChunks) {

        // figures out matching Arabic text by using String length and word
        // similarity
        String currArabicChunkLeaderStr = "";
        int currArabicChunkLeaderScore = -1;

        for (String candidateArabicChunk : arabicChunks) {

            if (candidateArabicChunk.matches("[\\s\\xA0]*")) {
                continue;
            }

            int normalizedStringLenWithNoSpaces = normalizedString.replaceAll("[\\s\\xA0]", "").length();

            String normalizedArabicChunk = new ArabicNormalizer(candidateArabicChunk).getOutput();
            int normalizedArabicChunkLenWithNoSpaces = normalizedArabicChunk.replaceAll("[\\s\\xA0]", "").length();
            int score = Math.abs(normalizedStringLenWithNoSpaces - normalizedArabicChunkLenWithNoSpaces);
            String[] normalizedWords = normalizedString.split("[\\s\\xA0]");
            String candidateArabicChunkCopy = normalizedArabicChunk;
            for (String normalizedWord : normalizedWords) {
                candidateArabicChunkCopy = candidateArabicChunkCopy.replace(normalizedWord, "");
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

    /**
     * Returns true if there more than half of the characters in the given
     * string are arabic letters.
     */
    public static boolean isProbablyArabic(String s) {
        if (s.matches("[\\s\\xA0]*")) {
            return false;
        }
        int sLen = s.length();
        int hits = 0;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            if (c >= 0x0600 && c <= 0x06E0)
                hits++;
            i += Character.charCount(c);
        }
        return (sLen / 2) <= hits;
    }

    public static String sendOCRAPIPost(File file) throws Exception {

        HttpPost httppost = new HttpPost("http://apipro3.ocr.space/parse/image");

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

    public static File getLatestFilefromDir(String dirPath) {
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
}

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

    /**
     * Returns true if any letters in the given String are
     * Arabic.
     */
    public static boolean containsArabic(String s) {
        if (s.matches("[\\s\\xA0]*")) {
            return false;
        }
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            if (c >= 0x0600 && c <= 0x06E0)
                return true;
            i += Character.charCount(c);
        }
        return false;
    }

    /**
     * Removes odd symbols from text to be used as english translation
     * of Hadith.
     */
    public static String cleanupEnglishLine(String dirtyLine) {
        dirtyLine = dirtyLine.replaceAll("[`’]", "");
        // Replace foot note numbers found after periods or commas if they exist.
        dirtyLine = dirtyLine.replaceAll("[\\.,][0-5] ", " ");
        return dirtyLine;
    }

    public static long convertWordToInteger(String word) {
        boolean isValidInput = true;
        long result = 0;
        long finalResult = 0;
        List<String> allowedStrings = Arrays.asList
                (
                        "zero", "one", "two", "three", "four", "five", "six", "seven",
                        "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
                        "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
                        "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
                        "hundred", "thousand", "million", "billion", "trillion"
                );

        String input = word;

        if (input != null && input.length() > 0) {
            input = input.replaceAll("[–-]", " ");
            input = input.toLowerCase().replaceAll(" and", " ");
            String[] splittedParts = input.trim().split("\\s+");

            for (String str : splittedParts) {
                if (!allowedStrings.contains(str)) {
                    isValidInput = false;
                    System.out.println("Invalid word found : " + str);
                    break;
                }
            }
            if (isValidInput) {
                for (String str : splittedParts) {
                    if (str.equalsIgnoreCase("zero")) {
                        result += 0;
                    } else if (str.equalsIgnoreCase("one")) {
                        result += 1;
                    } else if (str.equalsIgnoreCase("two")) {
                        result += 2;
                    } else if (str.equalsIgnoreCase("three")) {
                        result += 3;
                    } else if (str.equalsIgnoreCase("four")) {
                        result += 4;
                    } else if (str.equalsIgnoreCase("five")) {
                        result += 5;
                    } else if (str.equalsIgnoreCase("six")) {
                        result += 6;
                    } else if (str.equalsIgnoreCase("seven")) {
                        result += 7;
                    } else if (str.equalsIgnoreCase("eight")) {
                        result += 8;
                    } else if (str.equalsIgnoreCase("nine")) {
                        result += 9;
                    } else if (str.equalsIgnoreCase("ten")) {
                        result += 10;
                    } else if (str.equalsIgnoreCase("eleven")) {
                        result += 11;
                    } else if (str.equalsIgnoreCase("twelve")) {
                        result += 12;
                    } else if (str.equalsIgnoreCase("thirteen")) {
                        result += 13;
                    } else if (str.equalsIgnoreCase("fourteen")) {
                        result += 14;
                    } else if (str.equalsIgnoreCase("fifteen")) {
                        result += 15;
                    } else if (str.equalsIgnoreCase("sixteen")) {
                        result += 16;
                    } else if (str.equalsIgnoreCase("seventeen")) {
                        result += 17;
                    } else if (str.equalsIgnoreCase("eighteen")) {
                        result += 18;
                    } else if (str.equalsIgnoreCase("nineteen")) {
                        result += 19;
                    } else if (str.equalsIgnoreCase("twenty")) {
                        result += 20;
                    } else if (str.equalsIgnoreCase("thirty")) {
                        result += 30;
                    } else if (str.equalsIgnoreCase("forty")) {
                        result += 40;
                    } else if (str.equalsIgnoreCase("fifty")) {
                        result += 50;
                    } else if (str.equalsIgnoreCase("sixty")) {
                        result += 60;
                    } else if (str.equalsIgnoreCase("seventy")) {
                        result += 70;
                    } else if (str.equalsIgnoreCase("eighty")) {
                        result += 80;
                    } else if (str.equalsIgnoreCase("ninety")) {
                        result += 90;
                    } else if (str.equalsIgnoreCase("hundred")) {
                        result *= 100;
                    } else if (str.equalsIgnoreCase("thousand")) {
                        result *= 1000;
                        finalResult += result;
                        result = 0;
                    } else if (str.equalsIgnoreCase("million")) {
                        result *= 1000000;
                        finalResult += result;
                        result = 0;
                    } else if (str.equalsIgnoreCase("billion")) {
                        result *= 1000000000;
                        finalResult += result;
                        result = 0;
                    } else if (str.equalsIgnoreCase("trillion")) {
                        result *= 1000000000000L;
                        finalResult += result;
                        result = 0;
                    }
                }

                finalResult += result;
                result = 0;
            }
        }
        return finalResult;
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

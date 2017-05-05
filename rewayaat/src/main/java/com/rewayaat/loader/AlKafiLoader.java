package com.rewayaat.loader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.elasticsearch.action.index.IndexResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.web.config.ClientProvider;
import com.rewayaat.web.data.hadith.HadithObject;

public class AlKafiLoader {

	public static int currChapter = 0;
	public static String currentPart = "1: Al-Usul (principles)";
	public static String[] currentTags = {"intelligence", "ignorance"};
	public static HadithObject currentHadith = new HadithObject();
	public static StringBuilder currentArabicText = new StringBuilder();
	public static String book = "Al-Kafi";
	public static String currentSection;
	public static String volume = "1";
	public static String edition = "Tehran 5th edition Summer 1363/1978";

	public static void main(String[] args) throws Exception {
		PDDocument document = null;
		document = PDDocument.load(new File("/home/zir0/git/rewayaat/rewayaat/src/main/java/com/rewayaat/loader/alkafi.pdf"));
		try {
			// Create PDFTextStripper - used for searching the page string
			PDFTextStripper textStripper = new PDFTextStripper();
			// Loop through each page and search for "SEARCH STRING". If this
			// doesn't exist
			String st = textStripper.getText(document);
			// remove weird formatting symbols
			st = st.replaceAll("`~", "");
			st = st.replaceAll("www.alhassanain.org/english", "");

			
			// replace any double quotes with single quotes
			st = st.replaceAll("\"", "'");
			// start analyzing line by line
			String[] lines = st.split("\n");
			for (int i = 0; i < lines.length; i++) {
				if (lines[i].trim().isEmpty()) {
					// found empty line, skip!
					continue;
				} else if (lines[i].trim().equals("ww")) {
					// if we have found www.alhassanain.org/, skip!
					i = i + 12;
					continue;
				} else if (lines[i].matches("^\\s*\\d+\\s*$")) {
					// line only has a number on it - it is a page number, skip
					continue;
				} else if (isProbablyArabic(lines[i])) {
					System.out.println("original:\n" + lines[i]);
					System.out.println("AFTER: \n");
					currentArabicText.append(" " + lines[i].replaceAll("\n", "").replaceAll("\r", ""));
					// arabic text found, insert
				} else if (Character.isDigit(lines[i].trim().charAt(0))) {
					// new hadith found
					currentHadith = new HadithObject();
					String text = lines[i].substring(lines[i].trim().indexOf(" "), lines[i].length());
					currentHadith.insertEnglishText(text.replaceAll("\n", "").replaceAll("\r", ""));
					currentHadith.setArabic(currentArabicText.toString().replaceAll("\n", "").replaceAll("\r", ""));
					currentArabicText = new StringBuilder();
				} else if (currentHadith != null) {
					// english text found, insert
					currentHadith.insertEnglishText(lines[i].replaceAll("\n", "").replaceAll("\r", ""));
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("catch extract image");
		}
	}

	public static boolean isProbablyArabic(String s) {
		for (int i = 0; i < s.length();) {
			int c = s.codePointAt(i);
			if (c >= 0x0600 && c <= 0x06E0)
				return true;
			i += Character.charCount(c);
		}
		return false;
	}
}

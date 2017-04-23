package com.rewayaat.web.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds default amount of fuzziness to each term and phrase in the query.
 */
public class RewayaatQuery {

	private String query;

	public RewayaatQuery(String query) {
		this.query = query;
	}

	public String query() {
		System.out.println("Modifying query: " + query);
		StringBuilder newQuery = new StringBuilder();
		String[] terms = query.split("\"?([\\s\\xA0]|$)(?=(([^\"]*\"){2})*[^\"]*$)\"?");
		for (String term : terms) {
			if (term.length() > 0) {
				if (!term.matches(".*~[0-9].*") && !isProbablyArabic(term)) {
					// term does not have fuzziness applied to it yet...
					if (term.endsWith("\"") && term.startsWith("\"")) {
						// term is a phrase, add slop amount based on total
						// phrase
						// length.
						int slopAmount = (int) (term.split(" ").length * 0.3) + 2;
						System.out.println("Using slop value of " + slopAmount + " for phrase:\n" + term);
						newQuery.append(term + "~" + slopAmount + " ");
					} else {
						// otherwise apply default fuzziness of 2
						if (term.length() > 5) {
							newQuery.append(term + "~2 ");
						} else {
							newQuery.append(term + "~1 ");
						}
					}
				} else {
					// term already has fuzziness applied..
					newQuery.append(term + " ");
				}
			}
		}
		System.out.println("Modification complete, new query is: " + newQuery.toString());
		return newQuery.toString();
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

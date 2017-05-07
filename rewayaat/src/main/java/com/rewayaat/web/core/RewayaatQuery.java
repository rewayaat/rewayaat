package com.rewayaat.web.core;

/**
 * Uses Elastic Search Query Syntax to Modify user queries to improve search
 * results. This is done mostly by removing weird characters, adding <a href=
 * "https://www.elastic.co/guide/en/elasticsearch/guide/current/fuzziness.html">fuzziness</a>,
 * and adding <a href=
 * "https://www.elastic.co/guide/en/elasticsearch/guide/current/slop.html">slop</a>
 * to phrases. See {@linkplain RewayaatQueryTest} for expected behavior.
 */
public class RewayaatQuery {

    private String query;

    public RewayaatQuery(String query) {
        this.query = query;
    }

    public String query() {
        System.out.println("Modifying query: " + query);
        StringBuilder newQuery = new StringBuilder();
        // splits query by all spaces that are not enclosed by double quotes
        String[] terms = query.split("[\\s\\xA0]+(?=([^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String term : terms) {
            if (term.length() > 0) {
                if (!term.matches(".*~[0-9].*") && !isProbablyArabic(term) && !term.startsWith("_id:")) {
                    // term does not have fuzziness applied to it yet...
                    if (term.startsWith("\"")) {
                        // term is a phrase, add slop amount based on total
                        // phrase length.
                        int slopAmount = (int) (term.split(" ").length * 0.3) + 2;
                        System.out.println("Using slop value of " + slopAmount + " for phrase:\n" + term);
                        newQuery.append(term + "~" + slopAmount + " ");
                    } else {
                        // add default non-phrase fuzziness amount
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
            if (c >= 0x0600 && c <= 0x06E0) {
                return true;
            }
            i += Character.charCount(c);
        }
        return false;
    }
}

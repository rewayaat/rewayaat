package com.rewayaat.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Which narrations a backfill tool scrolls.
 *
 * <p>The tools were written for a whole-corpus pass and scroll {@code match_all}. Adding
 * books to a finished corpus needs the same pass over the new books only: rerunning it
 * over everything rewrites fields the embeddings were computed from. Set
 * {@code SEMANTIC_BACKFILL_BOOKS} to a {@code |}-separated list of {@code book} values.
 */
final class BackfillScope {

    static final String ENV = "SEMANTIC_BACKFILL_BOOKS";

    private BackfillScope() {
    }

    static ObjectNode query(ObjectMapper mapper, String books) {
        ObjectNode query = mapper.createObjectNode();
        if (books == null || books.isBlank()) {
            query.set("match_all", mapper.createObjectNode());
            return query;
        }
        ArrayNode values = mapper.createArrayNode();
        for (String book : books.split("\\|")) {
            if (!book.isBlank()) {
                values.add(book.trim());
            }
        }
        query.set("terms", mapper.createObjectNode().set("book", values));
        return query;
    }
}

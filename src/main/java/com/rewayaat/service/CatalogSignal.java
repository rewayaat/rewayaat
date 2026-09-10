package com.rewayaat.service;

import co.elastic.clients.elasticsearch._types.Refresh;
import com.rewayaat.config.ESClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * A shared marker saying when a narration was last edited.
 *
 * <p>{@link BookCatalog} holds the whole book, part and chapter structure in memory,
 * because rebuilding it costs a second and a half on a warm cluster and forty on a cold
 * one - far too much to do per request. Nothing told it when that structure changed, so an
 * edit was invisible for up to six hours, and a chapter's slug is derived from its title:
 * rename a bāb and the new URL does not resolve and the old one is still being advertised,
 * which reads as the chapter having vanished.
 *
 * <p>It cannot be an in-process flag. Several pods serve the site and each holds its own
 * catalog, so an edit handled by one leaves the others stale and the site disagrees with
 * itself depending on which pod answers. The marker lives in Elasticsearch, where every pod
 * can see it.
 *
 * <p>Reading it is a single get by id, which is cheap enough to do every half minute; the
 * expensive rebuild still happens only when the marker has actually moved.
 */
public final class CatalogSignal {

    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogSignal.class);

    private static final String INDEX = "rewayaat_meta";
    private static final String DOC_ID = "catalog";
    private static final String FIELD = "updatedAt";

    private CatalogSignal() {
    }

    /**
     * Records that the catalog's source data has changed.
     *
     * <p>Refreshed on write so the next reader sees it: a rename that takes a second to
     * appear is fine, one that takes a refresh interval to appear is the bug again in
     * miniature.
     *
     * <p>Never throws. This runs at the end of a successful edit, and an edit that saved
     * correctly must not be reported as failed because a cache hint could not be written -
     * the worst case without it is the six-hour staleness that already existed.
     */
    public static void touch() {
        try (ESClientProvider provider = new ESClientProvider()) {
            provider.client().index(i -> i
                    .index(INDEX)
                    .id(DOC_ID)
                    .document(Map.of(FIELD, Instant.now().toString()))
                    .refresh(Refresh.True));
        } catch (Exception e) {
            LOGGER.warn("Could not record the catalog change marker; "
                    + "book pages may lag this edit by up to the catalog TTL", e);
        }
    }

    /**
     * When the catalog's source data last changed, or {@link Instant#EPOCH} if unknown.
     *
     * <p>EPOCH covers both the marker never having been written and the read failing. Both
     * mean "no reason to rebuild", which leaves the time-based expiry as the backstop
     * rather than rebuilding the catalog on every request because a lookup is broken.
     */
    public static Instant lastChangedAt() {
        try (ESClientProvider provider = new ESClientProvider()) {
            var response = provider.client().get(g -> g.index(INDEX).id(DOC_ID), Map.class);
            if (!response.found() || response.source() == null) {
                return Instant.EPOCH;
            }
            Object value = response.source().get(FIELD);
            return value == null ? Instant.EPOCH : Instant.parse(value.toString());
        } catch (Exception e) {
            LOGGER.debug("Could not read the catalog change marker", e);
            return Instant.EPOCH;
        }
    }
}

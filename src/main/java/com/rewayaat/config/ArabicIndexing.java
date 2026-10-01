package com.rewayaat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether the Arabic site is allowed into search results yet.
 *
 * <p>Every other step of the Arabic release can be undone. The data load has a rollback
 * script and writes nothing the English site reads; the deploy can be reverted or the
 * image tag repointed. Being indexed cannot: once Google has roughly 3,900 Arabic URLs,
 * withdrawing them turns every one into a 404, and getting them back is slower than
 * losing them.
 *
 * <p>So that one irreversible step is separated from the deploy and made into a switch.
 * {@code /ar} is live, reachable and testable in production with this off; nothing
 * advertises it and nothing indexes it. Turning it on is a one-line change to the
 * deployment's environment — a sync rather than a build, the way
 * {@code OPENAI_APPS_CHALLENGE} already works — and it is the point of no return,
 * taken deliberately and on its own.
 *
 * <p>The default is {@code true}, which is the steady state: a development machine, a
 * test run and any future environment behave as the finished site does. Production holds
 * the {@code false} during the release, in {@code k8s/deployment.yaml} where it is
 * reviewable, and drops it afterwards.
 *
 * <p>Two things read this, and they have to agree. {@link CrawlerDirectivesConfig} puts
 * {@code X-Robots-Tag: noindex} on the Arabic tree — a header rather than a
 * {@code Disallow}, because a blocked URL still gets indexed from links and a crawler
 * that cannot fetch the page never reads the directive telling it to stay away. And
 * {@code SitemapController} lists only the English half of each pair, with no
 * {@code hreflang} annotation, because an alternate naming a noindex URL is a pair
 * Google discards rather than follows.
 */
@Component
public class ArabicIndexing {

    private final boolean indexable;

    public ArabicIndexing(@Value("${arabic.indexable:true}") boolean indexable) {
        this.indexable = indexable;
    }

    /** Whether Arabic URLs may be advertised and indexed. */
    public boolean isIndexable() {
        return indexable;
    }
}

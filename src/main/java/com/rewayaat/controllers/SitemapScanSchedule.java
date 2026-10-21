package com.rewayaat.controllers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * When the sitemap id scan may start, and when a stored list goes stale.
 *
 * <p>The scan reads every stored document and takes minutes in production, so when it
 * runs matters as much as how often. Three rules, kept apart from the controller so each
 * can be tested against a fixed clock:
 * <ul>
 *   <li><b>Quiet start.</b> Nothing may start before {@code firstScanAt}, a random
 *       moment after boot. On 2026-10-21 both pods scanned at boot while the book catalog
 *       was building and crawlers hit the cold pods, and the pile-up starved the
 *       Elasticsearch connection pool. The jitter keeps the two pods from scanning at
 *       the same moment. {@link #reset} leaves this alone: a reindex is no reason to
 *       break the quiet window.</li>
 *   <li><b>Staggered expiry.</b> A stored list is stale after the TTL plus a random
 *       jitter drawn when it is stored, so two pods that booted together drift apart.</li>
 *   <li><b>Back-off.</b> A failed scan blocks every new one for {@code retryAfterFailure};
 *       otherwise the very next request starts another against an Elasticsearch that
 *       has just shown it is struggling.</li>
 * </ul>
 * Single-flight is the controller's concern, not this class's. Thread-safe.
 */
final class SitemapScanSchedule {

    private final Clock clock;
    private final Duration ttl;
    private final Duration rescanJitter;
    private final Duration retryAfterFailure;
    private final LongUnaryOperator randomBelow;
    private final Instant firstScanAt;

    /** Null until a list is stored; set again by {@link #listStored}. */
    private Instant expiresAt;
    private Instant noScanBefore = Instant.EPOCH;

    SitemapScanSchedule(Clock clock, Duration ttl, Duration firstScanDelay, Duration firstScanJitter,
                        Duration rescanJitter, Duration retryAfterFailure, LongUnaryOperator randomBelow) {
        this.clock = clock;
        this.ttl = ttl;
        this.rescanJitter = rescanJitter;
        this.retryAfterFailure = retryAfterFailure;
        this.randomBelow = randomBelow;
        this.firstScanAt = clock.instant().plus(firstScanDelay).plus(jitter(firstScanJitter));
    }

    /** Production randomness: uniform over the jitter window. */
    static SitemapScanSchedule system(Duration ttl, Duration firstScanDelay, Duration firstScanJitter,
                                      Duration rescanJitter, Duration retryAfterFailure) {
        return new SitemapScanSchedule(Clock.systemUTC(), ttl, firstScanDelay, firstScanJitter,
                rescanJitter, retryAfterFailure,
                bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    /** The moment the first scan may start, fixed at construction. */
    Instant firstScanAt() {
        return firstScanAt;
    }

    /** How long until the first scan may start; zero or negative once that moment has passed. */
    synchronized Duration untilFirstScan() {
        return Duration.between(clock.instant(), firstScanAt);
    }

    /** True while the quiet start is still running. */
    synchronized boolean quiet() {
        return clock.instant().isBefore(firstScanAt);
    }

    /** May a scan start now: past the quiet start and not inside a failure back-off. */
    synchronized boolean mayStart() {
        Instant now = clock.instant();
        return !now.isBefore(firstScanAt) && !now.isBefore(noScanBefore);
    }

    /** A list has been stored; its expiry is the TTL plus a fresh random jitter. */
    synchronized void listStored() {
        expiresAt = clock.instant().plus(ttl).plus(jitter(rescanJitter));
    }

    /** True once a list has been stored and not since {@link #reset}. */
    synchronized boolean hasList() {
        return expiresAt != null;
    }

    /** True if a list is stored and its expiry has passed. */
    synchronized boolean stale() {
        return expiresAt != null && !clock.instant().isBefore(expiresAt);
    }

    /** Ages the stored list past its expiry, if there is one. */
    synchronized void expire() {
        if (expiresAt != null) {
            expiresAt = clock.instant();
        }
    }

    /** A scan failed: no new one may start for the back-off. */
    synchronized void scanFailed() {
        noScanBefore = clock.instant().plus(retryAfterFailure);
    }

    /** Forgets the stored list and any back-off. Deliberately keeps the quiet start. */
    synchronized void reset() {
        expiresAt = null;
        noScanBefore = Instant.EPOCH;
    }

    private Duration jitter(Duration window) {
        long millis = window.toMillis();
        return millis <= 0 ? Duration.ZERO : Duration.ofMillis(randomBelow.applyAsLong(millis + 1));
    }
}

package com.rewayaat.controllers;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SitemapScanScheduleTest {

    private static final Duration TTL = Duration.ofHours(24);

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-21T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    /** Randomness that always draws the top (or bottom) of the window. */
    private static SitemapScanSchedule schedule(MutableClock clock, boolean maxJitter) {
        return new SitemapScanSchedule(clock, TTL, Duration.ofMinutes(10), Duration.ofMinutes(10),
                Duration.ofMinutes(60), Duration.ofMinutes(15), bound -> maxJitter ? bound - 1 : 0);
    }

    @Test
    void firstScan_isDelayPlusJitter() {
        MutableClock clock = new MutableClock();
        Instant boot = clock.instant();
        assertEquals(boot.plus(Duration.ofMinutes(10)), schedule(clock, false).firstScanAt());
        assertEquals(boot.plus(Duration.ofMinutes(20)), schedule(clock, true).firstScanAt());
    }

    @Test
    void firstScan_fallsInsideItsWindowWithRealRandomness() {
        for (int i = 0; i < 200; i++) {
            SitemapScanSchedule s = SitemapScanSchedule.system(TTL, Duration.ofMinutes(10),
                    Duration.ofMinutes(10), Duration.ofMinutes(60), Duration.ofMinutes(15));
            // system() uses the real clock, so compare against it.
            Instant now = Instant.now();
            assertFalse(s.firstScanAt().isBefore(now.plus(Duration.ofMinutes(10)).minusSeconds(5)));
            assertFalse(s.firstScanAt().isAfter(now.plus(Duration.ofMinutes(20)).plusSeconds(5)));
        }
    }

    @Test
    void noScanMayStart_beforeTheFirstScanTime() {
        MutableClock clock = new MutableClock();
        SitemapScanSchedule s = schedule(clock, true);
        assertTrue(s.quiet());
        assertFalse(s.mayStart());
        clock.advance(Duration.ofMinutes(19));
        assertFalse(s.mayStart());
        clock.advance(Duration.ofMinutes(1));
        assertFalse(s.quiet());
        assertTrue(s.mayStart());
    }

    @Test
    void reset_doesNotRearmOrCancelTheQuietStart() {
        MutableClock clock = new MutableClock();
        SitemapScanSchedule s = schedule(clock, false);
        s.reset();
        assertFalse(s.mayStart(), "reset must not end the quiet start");
        clock.advance(Duration.ofMinutes(10));
        s.reset();
        assertTrue(s.mayStart(), "reset must not re-arm the quiet start");
    }

    @Test
    void expiry_fallsBetweenTtlAndTtlPlusJitter() {
        MutableClock clock = new MutableClock();
        for (boolean max : new boolean[] {false, true}) {
            SitemapScanSchedule s = schedule(clock, max);
            clock.advance(Duration.ofMinutes(30));
            s.listStored();
            Instant storedAt = clock.instant();
            assertFalse(s.stale());
            clock.advance(TTL.minusSeconds(1));
            assertFalse(s.stale(), "not stale before the TTL");
            clock.advance(Duration.ofSeconds(1));
            assertEquals(!max, s.stale(), "stale at the TTL only when the jitter drew zero");
            clock.advance(Duration.ofMinutes(60));
            assertTrue(s.stale(), "always stale past TTL + jitter");
            assertFalse(clock.instant().isBefore(storedAt.plus(TTL)));
        }
    }

    @Test
    void failure_blocksNewScansForTheBackOff_andOnlyThatLong() {
        MutableClock clock = new MutableClock();
        SitemapScanSchedule s = schedule(clock, false);
        clock.advance(Duration.ofMinutes(10));
        assertTrue(s.mayStart());
        s.scanFailed();
        assertFalse(s.mayStart());
        clock.advance(Duration.ofMinutes(14));
        assertFalse(s.mayStart());
        clock.advance(Duration.ofMinutes(1));
        assertTrue(s.mayStart());
    }

    @Test
    void reset_clearsTheBackOffAndTheStoredList() {
        MutableClock clock = new MutableClock();
        SitemapScanSchedule s = schedule(clock, false);
        clock.advance(Duration.ofMinutes(10));
        s.listStored();
        s.scanFailed();
        s.reset();
        assertTrue(s.mayStart());
        assertFalse(s.hasList());
    }

    @Test
    void expire_makesTheListStaleAtOnce_andIsANoOpWithoutAList() {
        MutableClock clock = new MutableClock();
        SitemapScanSchedule s = schedule(clock, true);
        s.expire();
        assertFalse(s.hasList());
        s.listStored();
        assertFalse(s.stale());
        s.expire();
        assertTrue(s.stale());
    }

    @Test
    void zeroDurations_behaveLikeNoScheduling() {
        MutableClock clock = new MutableClock();
        SitemapScanSchedule s = new SitemapScanSchedule(clock, TTL, Duration.ZERO, Duration.ZERO,
                Duration.ZERO, Duration.ZERO, bound -> {
                    throw new AssertionError("no randomness is needed for a zero window");
                });
        assertTrue(s.mayStart());
        s.scanFailed();
        assertTrue(s.mayStart());
    }
}

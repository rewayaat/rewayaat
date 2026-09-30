package com.rewayaat.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the limit on a crawler that spreads itself over many addresses: counted by what it says
 * it is, answered 429 past its budget, and never in the way of anyone else.
 */
class CrawlerRateLimitFilterTest {

    private static final String META = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 (compatible; meta-externalagent/1.1 "
            + "(+https://developers.facebook.com/docs/sharing/webmasters/crawler))";
    private static final String PERSON = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

    private final AtomicLong now = new AtomicLong(1_790_000_000_000L);
    private final CrawlerRateLimitFilter filter =
            new CrawlerRateLimitFilter("meta-externalagent", 3, now::get);

    /** The status the filter answered with, and whether the request reached the application. */
    private MockHttpServletResponse get(String path, String userAgent, String address) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(address);
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        response.setHeader("X-Reached-App", chain.getRequest() == null ? null : "yes");
        return response;
    }

    @Test
    void theCrawlerIsCountedAsAWholeAcrossTheAddressesItComesFrom() throws Exception {
        for (int request = 1; request <= 3; request++) {
            MockHttpServletResponse within = get("/hadith/x:" + request, META, "57.141.0." + request);
            assertEquals(200, within.getStatus());
            assertNotNull(within.getHeader("X-Reached-App"));
        }

        MockHttpServletResponse over = get("/hadith/x:4", META, "57.141.0.4");

        assertEquals(429, over.getStatus());
        assertNull(over.getHeader("X-Reached-App"), "a refused request must cost the application nothing");
        assertNotNull(over.getHeader("Retry-After"));
    }

    @Test
    void theBudgetIsNewEachMinute() throws Exception {
        for (int request = 0; request < 4; request++) {
            get("/books/a", META, "57.141.0.1");
        }
        assertEquals(429, get("/books/a", META, "57.141.0.1").getStatus());

        now.addAndGet(60_000L);

        assertEquals(200, get("/books/a", META, "57.141.0.1").getStatus());
    }

    @Test
    void nobodyElseIsCountedHoweverBusyTheCrawlerIs() throws Exception {
        for (int request = 0; request < 10; request++) {
            get("/books/a", META, "57.141.0.1");
        }

        assertEquals(200, get("/books/a", PERSON, "203.0.113.9").getStatus());
        assertEquals(200, get("/books/a", null, "203.0.113.9").getStatus());
    }

    @Test
    void robotsTxtIsAlwaysAnswered() throws Exception {
        for (int request = 0; request < 10; request++) {
            get("/books/a", META, "57.141.0.1");
        }

        assertEquals(200, get("/robots.txt", META, "57.141.0.1").getStatus());
    }

    @Test
    void aBudgetOfZeroTurnsTheLimitOff() throws Exception {
        CrawlerRateLimitFilter off = new CrawlerRateLimitFilter("meta-externalagent", 0, now::get);
        for (int request = 0; request < 50; request++) {
            MockHttpServletRequest asked = new MockHttpServletRequest("GET", "/books/a");
            asked.addHeader("User-Agent", META);
            MockHttpServletResponse response = new MockHttpServletResponse();
            off.doFilter(asked, response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }
    }
}

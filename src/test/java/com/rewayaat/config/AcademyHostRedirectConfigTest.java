package com.rewayaat.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The move off the Academy's host, and the things it must leave alone.
 *
 * <p>Two of these are not style points. Redirecting {@code /.well-known/} stops the
 * certificate for that host renewing, and the site breaks with TLS errors about sixty days
 * later, long after anyone would connect it to this change. Redirecting {@code /mcp}
 * breaks connectors already configured against that URL, silently, in other people's
 * clients.
 */
class AcademyHostRedirectConfigTest {

    private static final String ACADEMY = "hadith.academyofislam.com";
    private static final String CANADA = "99.228.28.198";
    private static final String ELSEWHERE = "8.8.8.8";

    private static CanadianAddresses canada;
    private static Filter filter;

    @BeforeAll
    static void load() {
        canada = new CanadianAddresses();
        filter = new AcademyHostRedirectConfig(canada).academyHostRedirectFilter().getFilter();
    }

    private static MockHttpServletResponse run(String host, String ip, String path, String query)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServerName(host);
        request.setRequestURI(path);
        request.setQueryString(query);
        request.addHeader("X-Forwarded-For", ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((MockHttpServletResponse) res).setStatus(200);
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void aVisitorOutsideCanadaIsMovedToTheCanonicalHost() throws Exception {
        MockHttpServletResponse response = run(ACADEMY, ELSEWHERE, "/books/al-kafi", null);
        assertEquals(301, response.getStatus());
        assertEquals("https://rewayaat.info/books/al-kafi", response.getHeader("Location"));
    }

    @Test
    void theRedirectKeepsThePathAndTheQuery() throws Exception {
        MockHttpServletResponse response = run(ACADEMY, ELSEWHERE, "/", "q=zakat&page=2");
        assertEquals("https://rewayaat.info/?q=zakat&page=2", response.getHeader("Location"));
    }

    @Test
    void aVisitorInCanadaStaysOnTheAcademysHost() throws Exception {
        MockHttpServletResponse response = run(ACADEMY, CANADA, "/books/al-kafi", null);
        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("Location"));
    }

    @Test
    void theCanonicalHostNeverRedirects() throws Exception {
        assertEquals(200, run("rewayaat.info", ELSEWHERE, "/books/al-kafi", null).getStatus());
        assertEquals(200, run("v2.rewayaat.info", ELSEWHERE, "/", null).getStatus());
    }

    /**
     * Let's Encrypt validates this host over HTTP from outside Canada. Redirect the
     * challenge and the certificate stops renewing.
     */
    @Test
    void theCertificateChallengeIsNeverRedirected() throws Exception {
        MockHttpServletResponse response =
                run(ACADEMY, ELSEWHERE, "/.well-known/acme-challenge/sometoken", null);
        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("Location"));
    }

    /** A connector URL lives in someone's client; it answers where it was configured to. */
    @Test
    void theConnectorEndpointIsNeverRedirected() throws Exception {
        assertEquals(200, run(ACADEMY, ELSEWHERE, "/mcp", null).getStatus());
        assertEquals(200, run(ACADEMY, ELSEWHERE, "/actuator/health", null).getStatus());
    }

    @Test
    void theRedirectIsNotStoredByASharedCache() throws Exception {
        MockHttpServletResponse response = run(ACADEMY, ELSEWHERE, "/", null);
        String cacheControl = response.getHeader("Cache-Control");
        assertTrue(cacheControl != null && cacheControl.contains("private"),
                "a response that varies by origin must not be cached for everyone: " + cacheControl);
    }

    @Test
    void theClientAddressComesFromTheProxyHeaderNotTheSocket() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.244.1.5");
        request.addHeader("X-Forwarded-For", CANADA + ", 10.244.0.1");
        assertEquals(CANADA, AcademyHostRedirectConfig.clientAddress(request));

        MockHttpServletRequest realIp = new MockHttpServletRequest();
        realIp.setRemoteAddr("10.244.1.5");
        realIp.addHeader("X-Real-IP", ELSEWHERE);
        assertEquals(ELSEWHERE, AcademyHostRedirectConfig.clientAddress(realIp));
    }

    @Test
    void theRangeTableRecognisesCanadaAndOnlyCanada() {
        assertTrue(canada.contains(CANADA));
        assertTrue(canada.contains("24.114.0.1"));
        assertFalse(canada.contains(ELSEWHERE));
        assertFalse(canada.contains("1.1.1.1"));
        // An address it cannot place is reported as outside Canada, so the failure is
        // towards the canonical host rather than away from it.
        assertFalse(canada.contains("not-an-address"));
        assertFalse(canada.contains(null));
        assertFalse(canada.contains(""));
    }
}

package net.dublinux.ignition.wake;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class WakeFilterTest {

    private static MockHttpServletRequest req(String method, String ua, String accept, String secFetchSite) {
        var r = new MockHttpServletRequest(method, "/");
        if (ua != null) r.addHeader("User-Agent", ua);
        if (accept != null) r.addHeader("Accept", accept);
        if (secFetchSite != null) r.addHeader("Sec-Fetch-Site", secFetchSite);
        return r;
    }

    private static final String CHROME = "Mozilla/5.0 (X11; Linux x86_64) Chrome/130";

    @Test
    void aBrowserNavigationWakesTheApp() {
        assertTrue(WakeFilter.isBrowserRequest(req("GET", CHROME, "text/html,application/xhtml+xml", "none"), "/"));
    }

    @Test
    void aFetchFromAPageServedOutOfTheBrowsersCacheWakesItToo() {
        // the stale-cached-page case: the document never reached the server, its script's API call does
        assertTrue(WakeFilter.isBrowserRequest(req("GET", CHROME, "application/json", "same-origin"), "/api/cards"));
        assertTrue(WakeFilter.isBrowserRequest(req("POST", CHROME, "*/*", "same-origin"), "/api/cards"));
    }

    @Test
    void probesCrawlersAndPlainApiClientsDoNot() {
        assertFalse(WakeFilter.isBrowserRequest(req("GET", "ignition-health-probe", "*/*", null), "/"));
        assertFalse(WakeFilter.isBrowserRequest(req("GET", "Googlebot/2.1", "text/html", "none"), "/"));
        assertFalse(WakeFilter.isBrowserRequest(req("GET", "curl/8.0", "*/*", null), "/"));
        assertFalse(WakeFilter.isBrowserRequest(req("GET", null, "text/html", null), "/"));
        assertFalse(WakeFilter.isBrowserRequest(req("GET", CHROME, "text/html", "none"), "/robots.txt"));
        assertFalse(WakeFilter.isBrowserRequest(req("GET", CHROME, "text/html", "none"), "/favicon.ico"));
        assertFalse(WakeFilter.isBrowserRequest(req("OPTIONS", CHROME, "*/*", "same-origin"), "/api"));
    }
}

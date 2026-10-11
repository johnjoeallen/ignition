package net.dublinux.ignition.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;
import net.dublinux.ignition.config.IgnitionProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppHealthProbeTest {

    private HttpServer server;
    private String base;
    private AppHealthProbe probe;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", x -> reply(x, 200));
        server.createContext("/sick", x -> reply(x, 503));
        server.createContext("/moved", x -> reply(x, 302));
        server.createContext("/", x -> reply(x, x.getRequestURI().getPath().equals("/") ? 401 : 404));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        probe = new AppHealthProbe(new IgnitionProperties());
    }

    private static void reply(com.sun.net.httpserver.HttpExchange x, int code) throws java.io.IOException {
        x.sendResponseHeaders(code, -1);
        x.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void explicitPathMustAnswer2xxOr3xx() {
        assertEquals(true, probe.up(base, "/ok"));
        assertEquals(true, probe.up(base, "/moved"));
        assertEquals(false, probe.up(base, "/sick"));
        assertEquals(false, probe.up(base, "/nope"), "404 on an explicit health path is a failure");
    }

    @Test
    void defaultCheckFailsOnlyOnNotFoundServerErrorOrNoAnswer() {
        assertEquals(true, probe.up(base, null), "401 at / is an app that answers");
        assertEquals(true, probe.up(base, ""));
        server.removeContext("/");
        server.createContext("/", x -> reply(x, 404));
        assertEquals(false, new AppHealthProbe(new IgnitionProperties()).up(base, null),
                "the edge's no-such-route 404");
    }

    @Test
    void unreachableIsDown() {
        server.stop(0);
        assertEquals(false, new AppHealthProbe(new IgnitionProperties()).up(base, "/ok"));
    }

    @Test
    void aBadPathLabelFallsBackToTheDefaultCheck() {
        // would be a host if concatenated naively; ignored, so this is the default / probe (401 -> up)
        assertEquals(true, probe.up(base, "@evil.example/ok"));
        assertEquals(true, probe.up(base, "/ok with space"));
    }
}

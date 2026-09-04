package net.dublinux.ignition.logging;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps the last {@link #CAPACITY} application log lines in memory, so the
 * console can show them without anyone needing to shell in and run
 * {@code docker logs} — which is where every debugging session this app has
 * ever needed actually starts. Attaches itself to the root logger at
 * construction; nothing to wire in a logback config file.
 */
@Service
public class RecentLogService extends AppenderBase<ILoggingEvent> {

    private static final int CAPACITY = 1000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Deque<String> lines = new ArrayDeque<>(CAPACITY);

    public RecentLogService() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        setContext(root.getLoggerContext());
        start();
        root.addAppender(this);
    }

    @Override
    protected void append(ILoggingEvent event) {
        String line = "%s %-5s [%s] %s%s".formatted(
                TIME.format(Instant.ofEpochMilli(event.getTimeStamp()).atZone(ZoneId.systemDefault())),
                event.getLevel(), shortLogger(event.getLoggerName()), event.getFormattedMessage(),
                throwableSuffix(event));
        synchronized (lines) {
            if (lines.size() >= CAPACITY) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }
    }

    /** Most recent first. */
    public List<String> recent() {
        synchronized (lines) {
            List<String> out = new ArrayList<>(lines);
            Collections.reverse(out);
            return out;
        }
    }

    private static String shortLogger(String name) {
        int lastDot = name.lastIndexOf('.');
        return lastDot < 0 ? name : name.substring(lastDot + 1);
    }

    private static String throwableSuffix(ILoggingEvent event) {
        var proxy = event.getThrowableProxy();
        return proxy == null ? "" : " — " + proxy.getClassName() + ": " + proxy.getMessage();
    }

    /** Just for a level badge in the UI. */
    public static boolean isWarnOrWorse(String line) {
        return line.contains(" WARN ") || line.contains(" ERROR ");
    }
}

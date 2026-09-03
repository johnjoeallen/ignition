package net.dublinux.ignition.state;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@code KEY=value} file — the on-disk format for every record under
 * {@code state/} (mirrors {@code _envfile} in the old Python control plane and
 * {@code *_get} in {@code lib.sh}). Blank lines and {@code #} comments are
 * skipped; the value is everything after the first {@code =}.
 */
public final class EnvFile {

    private EnvFile() {}

    public static Map<String, String> read(Path path) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            return out;
        }
        try {
            for (String raw : Files.readAllLines(path)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int eq = line.indexOf('=');
                out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + path, e);
        }
        return out;
    }

    public static void write(Path path, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        values.forEach((k, v) -> sb.append(k).append('=').append(v == null ? "" : v).append('\n'));
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + path, e);
        }
    }
}

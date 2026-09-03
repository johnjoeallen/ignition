package net.dublinux.ignition.zone;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.state.EnvFile;
import org.springframework.stereotype.Repository;

/** Zones live as {@code state/zones/<slug>/} directories. Read-only for now. */
@Repository
public class ZoneRepository {

    private final IgnitionProperties props;

    public ZoneRepository(IgnitionProperties props) {
        this.props = props;
    }

    public List<Zone> findAll() {
        Path dir = props.zonesDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(dir)) {
            List<Zone> zones = new ArrayList<>();
            dirs.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("zone.env")))
                    .sorted()
                    .forEach(p -> {
                        String slug = p.getFileName().toString();
                        zones.add(Zone.fromEnv(slug, EnvFile.read(p.resolve("zone.env"))));
                    });
            return zones;
        } catch (IOException e) {
            throw new UncheckedIOException("listing " + dir, e);
        }
    }

    public Optional<Zone> find(String slug) {
        Path env = props.zonesDir().resolve(slug).resolve("zone.env");
        return Files.isRegularFile(env) ? Optional.of(Zone.fromEnv(slug, EnvFile.read(env))) : Optional.empty();
    }

    public boolean exists(String slug) {
        return Files.isRegularFile(props.zonesDir().resolve(slug).resolve("zone.env"));
    }

    public Path dir(String slug) {
        return props.zonesDir().resolve(slug);
    }

    /** Write {@code state/zones/<slug>/zone.env} from a full key/value map. */
    public void saveEnv(String slug, java.util.Map<String, String> env) {
        EnvFile.write(dir(slug).resolve("zone.env"), env);
    }

    /** Remove the whole {@code state/zones/<slug>/} tree. */
    public void delete(String slug) {
        Path dir = dir(slug);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException("deleting " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("removing " + dir, e);
        }
    }

    /** A one-line secret file under the zone dir ({@code zone-token}, {@code deploy-token}, …). */
    public String secret(String slug, String name) {
        Path p = dir(slug).resolve(name);
        try {
            return Files.isRegularFile(p) ? Files.readString(p).strip() : "";
        } catch (IOException e) {
            return "";
        }
    }
}

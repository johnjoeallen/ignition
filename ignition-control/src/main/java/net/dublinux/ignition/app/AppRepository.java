package net.dublinux.ignition.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.state.EnvFile;
import net.dublinux.ignition.zone.ZoneRepository;
import org.springframework.stereotype.Repository;

/** Apps live as {@code state/zones/<slug>/apps/<name>.env}. */
@Repository
public class AppRepository {

    private final IgnitionProperties props;
    private final ZoneRepository zones;

    public AppRepository(IgnitionProperties props, ZoneRepository zones) {
        this.props = props;
        this.zones = zones;
    }

    public List<DeployedApp> findAll() {
        List<DeployedApp> all = new ArrayList<>();
        zones.findAll().forEach(z -> all.addAll(findByZone(z.slug())));
        return all;
    }

    /** {@code state/zones/<slug>/apps} */
    public Path dir(String slug) {
        return props.zonesDir().resolve(slug).resolve("apps");
    }

    public List<DeployedApp> findByZone(String slug) {
        Path dir = dir(slug);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<DeployedApp> apps = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().endsWith(".env"))
                    .sorted()
                    .forEach(p -> {
                        String name = p.getFileName().toString().replaceFirst("\\.env$", "");
                        apps.add(DeployedApp.fromEnv(slug, name, EnvFile.read(p)));
                    });
            return apps;
        } catch (IOException e) {
            throw new UncheckedIOException("listing " + dir, e);
        }
    }
}

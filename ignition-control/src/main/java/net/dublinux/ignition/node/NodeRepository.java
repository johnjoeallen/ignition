package net.dublinux.ignition.node;

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

/** Nodes live as {@code state/nodes/<name>.env}. */
@Repository
public class NodeRepository {

    private final IgnitionProperties props;

    public NodeRepository(IgnitionProperties props) {
        this.props = props;
    }

    public List<Node> findAll() {
        Path dir = props.nodesDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Node> nodes = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().endsWith(".env"))
                    .sorted()
                    .forEach(p -> {
                        String name = p.getFileName().toString().replaceFirst("\\.env$", "");
                        nodes.add(Node.fromEnv(name, EnvFile.read(p)));
                    });
            return nodes;
        } catch (IOException e) {
            throw new UncheckedIOException("listing " + dir, e);
        }
    }

    public Optional<Node> find(String name) {
        Path p = file(name);
        return Files.isRegularFile(p) ? Optional.of(Node.fromEnv(name, EnvFile.read(p))) : Optional.empty();
    }

    public void save(Node node) {
        EnvFile.write(file(node.name()), node.toEnv());
    }

    public void delete(String name) {
        try {
            Files.deleteIfExists(file(name));
        } catch (IOException e) {
            throw new UncheckedIOException("deleting node " + name, e);
        }
    }

    private Path file(String name) {
        return props.nodesDir().resolve(name + ".env");
    }
}

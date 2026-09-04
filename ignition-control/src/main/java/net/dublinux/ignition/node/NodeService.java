package net.dublinux.ignition.node;

import java.util.List;
import java.util.regex.Pattern;

import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class NodeService {

    /** A valid docker-host endpoint: {@code local}, {@code unix://…}, {@code ssh://…}, {@code tcp://…}. */
    private static final Pattern ENDPOINT =
            Pattern.compile("^(local|unix://.+|ssh://.+|tcp://.+)$");
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,38}[a-z0-9]$");

    private final NodeRepository nodes;
    private final ZoneRepository zones;

    public NodeService(NodeRepository nodes, ZoneRepository zones) {
        this.nodes = nodes;
        this.zones = zones;
    }

    public List<Node> list() {
        return nodes.findAll(Sort.by("name"));
    }

    public Node register(String name, String dockerHost, double cpus, double memGb, List<String> labels) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("node name must be [a-z0-9-], 2–40 chars");
        }
        if (!ENDPOINT.matcher(dockerHost).matches()) {
            throw new IllegalArgumentException("docker host must be local, unix://, ssh:// or tcp://");
        }
        if (cpus <= 0 || memGb <= 0) {
            throw new IllegalArgumentException("cpus and mem must be > 0");
        }
        Node node = new Node(name, dockerHost, cpus, memGb,
                labels == null ? List.of() : labels, Node.State.ACTIVE);
        return nodes.save(node);
    }

    public void setState(String name, Node.State state) {
        Node n = nodes.findById(name).orElseThrow(() -> new IllegalArgumentException("no such node: " + name));
        n.setState(state);
        nodes.save(n);
    }

    public void remove(String name) {
        long assigned = zones.findAll().stream().filter(z -> name.equals(z.node())).count();
        if (assigned > 0) {
            throw new IllegalStateException(assigned + " zone(s) still assigned to " + name);
        }
        nodes.deleteById(name);
    }

    /** CPU / memory / zone-count currently committed to a node (limits, not reservations). */
    public Allocation allocation(String name) {
        double cpu = 0;
        double mem = 0;
        int count = 0;
        for (Zone z : zones.findAll()) {
            if (name.equals(z.node())) {
                cpu += z.zoneCpus();
                mem += z.zoneMemGb();
                count++;
            }
        }
        return new Allocation(cpu, mem, count);
    }

    public record Allocation(double cpus, double memGb, int zones) {}
}

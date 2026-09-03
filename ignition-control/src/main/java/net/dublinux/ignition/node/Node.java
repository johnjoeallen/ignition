package net.dublinux.ignition.node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A host that runs zone stacks. Persisted as {@code state/nodes/<name>.env}
 * with keys {@code DOCKER_HOST, CPUS, MEM_GB, LABELS, STATE}.
 */
public record Node(
        String name,
        String dockerHost,
        double cpus,
        double memGb,
        List<String> labels,
        State state) {

    public enum State { ACTIVE, DRAINING }

    public static Node fromEnv(String name, Map<String, String> env) {
        String labels = env.getOrDefault("LABELS", "").strip();
        return new Node(
                name,
                env.getOrDefault("DOCKER_HOST", "local"),
                parseDouble(env.get("CPUS"), 0),
                parseDouble(env.get("MEM_GB"), 0),
                labels.isEmpty() ? List.of() : List.of(labels.split("\\s*,\\s*")),
                "draining".equalsIgnoreCase(env.getOrDefault("STATE", "active"))
                        ? State.DRAINING : State.ACTIVE);
    }

    public Map<String, String> toEnv() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("DOCKER_HOST", dockerHost);
        m.put("CPUS", trim(cpus));
        m.put("MEM_GB", trim(memGb));
        m.put("LABELS", String.join(",", labels));
        m.put("STATE", state.name().toLowerCase());
        return m;
    }

    public boolean hasLabel(String label) {
        return label == null || label.isBlank() || labels.contains(label);
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return s == null || s.isBlank() ? fallback : Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? Long.toString((long) d) : Double.toString(d);
    }
}

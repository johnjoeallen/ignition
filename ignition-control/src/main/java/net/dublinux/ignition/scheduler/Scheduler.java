package net.dublinux.ignition.scheduler;

import net.dublinux.ignition.node.Node;
import net.dublinux.ignition.node.NodeService;
import org.springframework.stereotype.Component;

/**
 * Node placement — CPU-headroom first. Picks the active node with the most free
 * CPU (capacity minus the sum of assigned zones' quota limits) that can fit the
 * zone and carries any required label. Quotas are limits, not reservations, so
 * nodes oversubscribe — but a zone whose limits alone exceed a node is never
 * placed there. Mirrors {@code pick_node()} in {@code scheduler.sh}.
 */
@Component
public class Scheduler {

    private final NodeService nodes;

    public Scheduler(NodeService nodes) {
        this.nodes = nodes;
    }

    public String place(double needCpu, double needMemGb, String label) {
        String best = null;
        double bestFree = -1;
        for (Node n : nodes.list()) {
            if (n.state() != Node.State.ACTIVE || !n.hasLabel(label)) {
                continue;
            }
            if (needCpu > n.cpus() || needMemGb > n.memGb()) {
                continue;
            }
            double free = n.cpus() - nodes.allocation(n.name()).cpus();
            if (free > bestFree) {
                best = n.name();
                bestFree = free;
            }
        }
        if (best == null) {
            String withLabel = (label == null || label.isBlank()) ? "" : " with label '" + label + "'";
            throw new IllegalStateException(
                    "no active node can fit a zone needing %.1f cpu / %.0fg%s"
                            .formatted(needCpu, needMemGb, withLabel));
        }
        return best;
    }
}

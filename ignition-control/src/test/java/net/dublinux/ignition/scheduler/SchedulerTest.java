package net.dublinux.ignition.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.node.Node;
import net.dublinux.ignition.node.NodeRepository;
import net.dublinux.ignition.node.NodeService;
import net.dublinux.ignition.zone.ZoneRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchedulerTest {

    private Scheduler scheduler(Path state, Node... nodes) {
        IgnitionProperties props = new IgnitionProperties();
        props.setStateDir(state);
        NodeRepository nodeRepo = new NodeRepository(props);
        for (Node n : nodes) {
            nodeRepo.save(n);
        }
        NodeService nodeService = new NodeService(nodeRepo, new ZoneRepository(props));
        return new Scheduler(nodeService);
    }

    @Test
    void picksTheActiveNodeWithMostFreeCpu(@TempDir Path state) {
        var s = scheduler(state,
                new Node("big", "local", 32, 128, List.of(), Node.State.ACTIVE),
                new Node("small", "local", 4, 8, List.of(), Node.State.ACTIVE),
                new Node("drained", "local", 64, 256, List.of(), Node.State.DRAINING));
        assertThat(s.place(4, 8, null)).isEqualTo("big");
    }

    @Test
    void honoursLabelsAndCapacity(@TempDir Path state) {
        var s = scheduler(state,
                new Node("plain", "local", 32, 128, List.of(), Node.State.ACTIVE),
                new Node("fast", "local", 8, 16, List.of("fast"), Node.State.ACTIVE));
        assertThat(s.place(4, 8, "fast")).isEqualTo("fast");
        assertThatThrownBy(() -> s.place(40, 8, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.place(1, 1, "gpu")).isInstanceOf(IllegalStateException.class);
    }
}

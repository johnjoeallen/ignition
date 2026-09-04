package net.dublinux.ignition.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import net.dublinux.ignition.node.Node;
import net.dublinux.ignition.node.NodeService;
import net.dublinux.ignition.node.NodeService.Allocation;
import org.junit.jupiter.api.Test;

class SchedulerTest {

    private Scheduler scheduler(Node... nodes) {
        NodeService svc = mock(NodeService.class);
        when(svc.list()).thenReturn(List.of(nodes));
        when(svc.allocation(anyString())).thenReturn(new Allocation(0, 0, 0));
        return new Scheduler(svc);
    }

    @Test
    void picksTheActiveNodeWithMostFreeCpu() {
        var s = scheduler(
                new Node("big", "local", 32, 128, List.of(), Node.State.ACTIVE),
                new Node("small", "local", 4, 8, List.of(), Node.State.ACTIVE),
                new Node("drained", "local", 64, 256, List.of(), Node.State.DRAINING));
        assertThat(s.place(4, 8, null)).isEqualTo("big");
    }

    @Test
    void honoursLabelsAndCapacity() {
        var s = scheduler(
                new Node("plain", "local", 32, 128, List.of(), Node.State.ACTIVE),
                new Node("fast", "local", 8, 16, List.of("fast"), Node.State.ACTIVE));
        assertThat(s.place(4, 8, "fast")).isEqualTo("fast");
        assertThatThrownBy(() -> s.place(40, 8, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.place(1, 1, "gpu")).isInstanceOf(IllegalStateException.class);
    }
}

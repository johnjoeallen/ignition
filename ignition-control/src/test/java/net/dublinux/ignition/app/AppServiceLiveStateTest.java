package net.dublinux.ignition.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.dublinux.ignition.docker.DockerCli;
import net.dublinux.ignition.node.NodeRepository;
import net.dublinux.ignition.templates.RenderService;
import net.dublinux.ignition.zone.Zone;
import net.dublinux.ignition.zone.ZoneRepository;
import net.dublinux.ignition.zone.ZoneService;
import org.junit.jupiter.api.Test;

/** The console's Stop/Start follows the containers, not the stored running flag. */
class AppServiceLiveStateTest {

    private static DeployedApp app(String name) {
        DeployedApp a = new DeployedApp("t", name, "local", "img", 8080, "d1");
        a.markRunning();
        return a;
    }

    private static Map<String, Boolean> states(String psOut) {
        AppRepository apps = mock(AppRepository.class);
        when(apps.findByZone("t")).thenReturn(List.of(
                app("up"), app("sick"), app("gone"), app("partial")));
        DockerCli docker = mock(DockerCli.class);
        when(docker.docker(anyString(), anyList()))
                .thenReturn(new DockerCli.Result(0, psOut, ""));
        AppService svc = new AppService(apps, mock(DevDeploymentRepository.class),
                mock(ZoneRepository.class), mock(ZoneService.class), mock(NodeRepository.class),
                docker, mock(RenderService.class), mock(AppComposeBuilder.class), mock(AppHealthProbe.class));
        return svc.liveStates("t");
    }

    @Test
    void liveOnlyWhenEveryContainerRunsAndNoneIsUnhealthy() {
        Map<String, Boolean> s = states(String.join("\n",
                "app-t-up\trunning\tUp 2 weeks (healthy)",
                "app-t-sick\trunning\tUp 2 weeks (unhealthy)",
                "app-t-partial\trunning\tUp 2 weeks",
                "app-t-partial\texited\tExited (1) 3 hours ago",
                "zone-t\trunning\tUp 2 weeks",
                ""));
        assertEquals(true, s.get("up"));
        assertEquals(false, s.get("sick"), "unhealthy -> offer Start");
        assertEquals(false, s.get("gone"), "no containers -> offer Start even though the flag says running");
        assertEquals(false, s.get("partial"), "a dead sidecar -> not live");
    }

    @Test
    void fallsBackToTheFlagWhenTheNodeCannotBeAsked() {
        AppRepository apps = mock(AppRepository.class);
        when(apps.findByZone("t")).thenReturn(List.of(app("up")));
        DockerCli docker = mock(DockerCli.class);
        when(docker.docker(anyString(), anyList())).thenReturn(new DockerCli.Result(1, "", "cannot connect"));
        AppService svc = new AppService(apps, mock(DevDeploymentRepository.class),
                mock(ZoneRepository.class), mock(ZoneService.class), mock(NodeRepository.class),
                docker, mock(RenderService.class), mock(AppComposeBuilder.class), mock(AppHealthProbe.class));
        assertEquals(true, svc.liveStates("t").get("up"));
    }
}

class AppServiceHealthProbeTest {

    @Test
    void anAppWhoseContainersAreUpButFailsItsHealthCheckIsNotLive() {
        AppRepository apps = mock(AppRepository.class);
        when(apps.findByZone("t")).thenReturn(List.of(app("ok"), app("wedged"), app("down")));
        DockerCli docker = mock(DockerCli.class);
        when(docker.docker(anyString(), anyList())).thenReturn(new DockerCli.Result(0, String.join("\n",
                "app-t-ok\trunning\tUp 2 days\t/actuator/health",
                "app-t-wedged\trunning\tUp 2 days\t",
                ""), ""));
        ZoneRepository zones = mock(ZoneRepository.class);
        Zone z = mock(Zone.class);
        when(z.baseDomain()).thenReturn("d.test");
        when(zones.find("t")).thenReturn(Optional.of(z));
        AppHealthProbe probe = mock(AppHealthProbe.class);
        when(probe.up(eq("https://ok.apps.t.d.test/"), eq("/actuator/health"))).thenReturn(true);
        when(probe.up(eq("https://wedged.apps.t.d.test/"), eq(""))).thenReturn(false);
        AppService svc = new AppService(apps, mock(DevDeploymentRepository.class), zones,
                mock(ZoneService.class), mock(NodeRepository.class), docker,
                mock(RenderService.class), mock(AppComposeBuilder.class), probe);

        Map<String, Boolean> s = svc.liveStates("t");
        assertEquals(true, s.get("ok"), "the label's path is what gets probed");
        assertEquals(false, s.get("wedged"), "containers up but the URL fails -> offer Start");
        assertEquals(false, s.get("down"), "no containers -> not probed, offer Start");
    }

    private static DeployedApp app(String name) {
        DeployedApp a = new DeployedApp("t", name, "local", "img", 8080, "d1");
        a.markRunning();
        return a;
    }
}

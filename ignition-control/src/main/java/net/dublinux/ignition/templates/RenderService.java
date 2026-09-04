package net.dublinux.ignition.templates;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import net.dublinux.ignition.config.IgnitionProperties;
import net.dublinux.ignition.config.IgnitionProperties.Quotas;
import net.dublinux.ignition.zone.Zone;
import org.springframework.stereotype.Service;

/**
 * PostgreSQL is the source of truth; this re-materialises the files external
 * tools still need — the zone / app compose files, the runner config — into the
 * ephemeral work dir, from the current row state, right before the {@code docker}
 * call that reads them. Every method is idempotent and safe to call on a fresh
 * (wiped) work dir.
 */
@Service
public class RenderService {

    private final ComposeTemplate templates;
    private final IgnitionProperties props;

    public RenderService(ComposeTemplate templates, IgnitionProperties props) {
        this.templates = templates;
        this.props = props;
    }

    /** {@code <work>/zones/<slug>/docker-compose.yml} — returns its path. */
    public Path zoneCompose(Zone zone) {
        Quotas q = props.getQuotas();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("ZONE_SLUG", zone.slug());
        vars.put("BASE_DOMAIN", zone.baseDomain());
        vars.put("CPU_FORGEJO", num(q.getCpuForgejo()));
        vars.put("MEM_FORGEJO", q.getMemForgejo());
        vars.put("CPU_DIND", num(q.getCpuDind()));
        vars.put("MEM_DIND", q.getMemDind());
        vars.put("CPU_RUNNER", num(q.getCpuRunner()));
        vars.put("MEM_RUNNER", q.getMemRunner());
        return write(props.zoneWorkDir(zone.slug()).resolve("docker-compose.yml"),
                templates.renderZone(vars));
    }

    /** {@code <work>/zones/<slug>/apps/<name>-compose.yml} — returns its path. */
    public Path appCompose(String slug, String name, String baseDomain,
                           String image, int port, String deployId) {
        Quotas q = props.getQuotas();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("APP_NAME", name);
        vars.put("ZONE_SLUG", slug);
        vars.put("BASE_DOMAIN", baseDomain);
        vars.put("APP_IMAGE", image);
        vars.put("APP_PORT", Integer.toString(port));
        vars.put("DEPLOY_ID", deployId);
        vars.put("CPU_APP", Double.toString(q.getCpuApp()));
        vars.put("MEM_APP", q.getMemApp());
        return write(props.appWorkDir(slug).resolve(name + "-compose.yml"),
                templates.renderApp(vars));
    }

    /** {@code <work>/zones/<slug>/runner-config.yml} — returns its path. */
    public Path runnerConfig(String slug, int capacity, String uuid, String secret) {
        String yaml = """
                log: { level: info }
                runner:
                  file: /data/.runner
                  capacity: %d
                  timeout: 3h
                  envs:
                    DOCKER_HOST: tcp://dind:2375
                  labels:
                    - "ubuntu-latest:docker://code.forgejo.org/oci/node:22-bookworm"
                    - "docker-cli:docker://code.forgejo.org/oci/docker:cli"
                container: { network: host, valid_volumes: [] }
                server:
                  connections:
                    default: { url: "http://forgejo:3000", uuid: "%s", token: "%s" }
                """.formatted(capacity, uuid, secret);
        return write(props.zoneWorkDir(slug).resolve("runner-config.yml"), yaml);
    }

    public Path write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + path, e);
        }
    }

    private static String num(double d) {
        return d == Math.floor(d) ? "%.1f".formatted(d) : Double.toString(d);
    }
}

package net.dublinux.ignition.docker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the {@code docker} CLI against a node's daemon — {@code -H <endpoint>}
 * for anything other than {@code local}. All compose operations go through
 * here (see DESIGN.md "Docker engine access"): the same commands the shell
 * scripts run today, so the SSH-safe {@code compose cp} trick carries over.
 */
@Component
public class DockerCli {

    private static final Logger log = LoggerFactory.getLogger(DockerCli.class);

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** {@code docker [-H <endpoint>] <args...>} */
    public Result docker(String dockerHost, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        if (dockerHost != null && !dockerHost.isBlank() && !dockerHost.equals("local")) {
            cmd.add("-H");
            cmd.add(dockerHost);
        }
        cmd.addAll(args);
        return run(cmd);
    }

    /** {@code docker [-H …] compose -p <project> [-f <file>] <args...>} */
    public Result compose(String dockerHost, String project, String composeFile, String... args) {
        List<String> a = new ArrayList<>(List.of("compose", "-p", project));
        if (composeFile != null && !composeFile.isBlank()) {
            a.add("-f");
            a.add(composeFile);
        }
        a.addAll(List.of(args));
        return docker(dockerHost, a);
    }

    private Result run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new Result(-1, out, "timed out: " + String.join(" ", cmd));
            }
            int code = p.exitValue();
            if (code != 0) {
                log.debug("{} -> {} : {}", String.join(" ", cmd), code, err.strip());
            }
            return new Result(code, out, err);
        } catch (IOException e) {
            return new Result(-1, "", "docker not runnable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "interrupted");
        }
    }
}

package net.dublinux.ignition.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the operator sets. Bound from {@code application.yml} / env
 * ({@code IGNITION_*}, or the legacy {@code IGN_*} / {@code BASE_DOMAIN} via
 * {@code application.yml}).
 */
@ConfigurationProperties(prefix = "ignition")
public class IgnitionProperties {

    /** Apex the install is hosted on, e.g. {@code ignition.example}. */
    private String baseDomain = "ignition.example";

    /** Platform-admin bearer token. Empty disables the platform console. */
    private String adminToken = "";

    /**
     * Ephemeral scratch dir for files external tools read (rendered compose,
     * runner config, Traefik dynamic snippets). Regenerated from the DB; safe
     * to wipe.
     */
    private Path workDir = Path.of("../work");

    /** 32 bytes base64 — AES-GCM key for {@code zone_secret} values. */
    private String secretKey = "";

    /** Skip TLS verification when calling a zone's Forgejo (pre-cert). */
    private boolean insecureTls = false;

    private final Sweep sweep = new Sweep();
    private final Quotas quotas = new Quotas();

    public static class Sweep {
        /** Reclaim a zone idle longer than this. */
        private Duration ttl = Duration.ofHours(24);
        /** How often the sweeper runs. */
        private Duration interval = Duration.ofMinutes(15);

        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
    }

    /** Per-zone resource limits used for node capacity accounting. */
    public static class Quotas {
        private double cpuForgejo = 1.0;
        private String memForgejo = "1g";
        private double cpuDind = 2.0;
        private String memDind = "4g";
        private double cpuRunner = 1.0;
        private String memRunner = "2g";
        private double cpuApp = 1.0;
        private String memApp = "1g";
        private int runnerCapacity = 4;

        public double getCpuForgejo() { return cpuForgejo; }
        public void setCpuForgejo(double v) { this.cpuForgejo = v; }
        public String getMemForgejo() { return memForgejo; }
        public void setMemForgejo(String v) { this.memForgejo = v; }
        public double getCpuDind() { return cpuDind; }
        public void setCpuDind(double v) { this.cpuDind = v; }
        public String getMemDind() { return memDind; }
        public void setMemDind(String v) { this.memDind = v; }
        public double getCpuRunner() { return cpuRunner; }
        public void setCpuRunner(double v) { this.cpuRunner = v; }
        public String getMemRunner() { return memRunner; }
        public void setMemRunner(String v) { this.memRunner = v; }
        public double getCpuApp() { return cpuApp; }
        public void setCpuApp(double v) { this.cpuApp = v; }
        public String getMemApp() { return memApp; }
        public void setMemApp(String v) { this.memApp = v; }
        public int getRunnerCapacity() { return runnerCapacity; }
        public void setRunnerCapacity(int v) { this.runnerCapacity = v; }
    }

    public String getBaseDomain() { return baseDomain; }
    public void setBaseDomain(String baseDomain) { this.baseDomain = baseDomain; }
    public String getAdminToken() { return adminToken; }
    public void setAdminToken(String adminToken) { this.adminToken = adminToken; }
    public Path getWorkDir() { return workDir; }
    public void setWorkDir(Path workDir) { this.workDir = workDir; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public boolean isInsecureTls() { return insecureTls; }
    public void setInsecureTls(boolean insecureTls) { this.insecureTls = insecureTls; }
    public Sweep getSweep() { return sweep; }
    public Quotas getQuotas() { return quotas; }

    public Path zoneWorkDir(String slug) { return workDir.resolve("zones").resolve(slug); }
    public Path appWorkDir(String slug) { return zoneWorkDir(slug).resolve("apps"); }
    public Path controlDynamicDir() { return workDir.resolve("control").resolve("dynamic"); }
}

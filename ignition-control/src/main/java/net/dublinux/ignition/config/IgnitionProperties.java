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

    /**
     * Ephemeral scratch dir for files external tools read (rendered compose,
     * runner config, Traefik dynamic snippets). Regenerated from the DB; safe
     * to wipe.
     */
    private Path workDir = Path.of("../work");

    /** 32 bytes base64 — AES-GCM key for {@code zone_secret} values. */
    private String secretKey = "";

    /**
     * A platform-wide secret UUID, combined with a user's own id when
     * deriving their per-user secret key ({@code UserSecretCipher}) — without
     * it, that derivation was PBKDF2 over the user's UUID alone, which is
     * sitting right there in the same database row it protects, so a
     * {@code pg_dump} alone (no app config) was enough to brute-force every
     * user's git password/PAT. This value lives only in config, never in the
     * database, so leaking the DB alone no longer leaks the means to decrypt it.
     */
    private String userSecretPepper = "";

    /** Public origin, e.g. {@code https://ignition.example} — used to build email links. */
    private String publicUrl = "http://localhost:8790";

    /** Skip TLS verification when calling a zone's Forgejo (pre-cert). */
    private boolean insecureTls = false;

    /**
     * Whether every team's org and its repos are private (git-clone/browse
     * requires an org membership + login) or public (anyone who resolves the
     * host can clone with no authentication at all). Platform-wide, not
     * per-team — a corporate deployment wants this true; the original design
     * defaulted to public on the reasoning that "no SSO, so private just
     * hides work from teammates" — true for a same-team view, but it also
     * means literally anyone on the network can clone, which isn't
     * acceptable outside a fully trusted/isolated demo. Defaults true now.
     */
    private boolean privateRepos = true;

    /**
     * Recreate every zone's stack (compose {@code down} + {@code up -d}, never
     * {@code -v}) once on startup — how a compose/label/image change (e.g. a
     * new Traefik router label) reaches zones that are already running,
     * without a manual per-zone step. Off by default (a routine restart
     * shouldn't cause zone downtime); {@code update-and-run.sh} turns it on
     * for its own run. Never touches a volume, so no zone data (Forgejo repos,
     * its host keys, the DB-stored tokens/PATs) is at risk either way.
     */
    private boolean recreateZonesOnStart = false;

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
    public Path getWorkDir() { return workDir; }
    public void setWorkDir(Path workDir) { this.workDir = workDir; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getUserSecretPepper() { return userSecretPepper; }
    public void setUserSecretPepper(String v) { this.userSecretPepper = v; }
    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
    public boolean isInsecureTls() { return insecureTls; }
    public void setInsecureTls(boolean insecureTls) { this.insecureTls = insecureTls; }
    public boolean isPrivateRepos() { return privateRepos; }
    public void setPrivateRepos(boolean v) { this.privateRepos = v; }
    public boolean isRecreateZonesOnStart() { return recreateZonesOnStart; }
    public void setRecreateZonesOnStart(boolean v) { this.recreateZonesOnStart = v; }
    public Sweep getSweep() { return sweep; }
    public Quotas getQuotas() { return quotas; }

    public Path zoneWorkDir(String slug) { return workDir.resolve("zones").resolve(slug); }
    public Path appWorkDir(String slug) { return zoneWorkDir(slug).resolve("apps"); }
    public Path controlDynamicDir() { return workDir.resolve("control").resolve("dynamic"); }
}

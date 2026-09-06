package net.dublinux.ignition.app;

/**
 * Which host an app deployment targets.
 *
 * <ul>
 *   <li>{@link #RELEASE} — {@code <name>.apps.<slug>.<BASE_DOMAIN>}, from a
 *       release tag. Compose project {@code app-<slug>-<name>}.</li>
 *   <li>{@link #DEV} — {@code <name>.dev.<slug>.<BASE_DOMAIN>}, the latest
 *       {@code main} HEAD, shipped only when someone clicks "Deploy from main"
 *       in the team console. Compose project {@code app-<slug>-<name>-dev}.
 *       Publicly reachable, same as the release host — the button is the only
 *       gate.</li>
 * </ul>
 */
public enum Channel {
    RELEASE, DEV;

    /** The label between the app name and the slug: {@code apps} or {@code dev}. */
    public String subdomain() {
        return this == DEV ? "dev" : "apps";
    }

    /** Compose-project / container / router suffix: empty, or {@code -dev}. */
    public String projectSuffix() {
        return this == DEV ? "-dev" : "";
    }

    public static Channel fromPayload(String raw) {
        return "dev".equalsIgnoreCase(raw == null ? "" : raw.strip()) ? DEV : RELEASE;
    }
}

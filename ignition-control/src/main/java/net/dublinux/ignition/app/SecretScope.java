package net.dublinux.ignition.app;

import java.util.Locale;

/** Which deployment of an app a secret is for. */
public enum SecretScope {
    /** A tagged release, served at {@code <app>.apps.<zone>…}. */
    RELEASE,
    /** The deploy-from-main host, {@code <app>.dev.<zone>…}. */
    DEV,
    /** Every PR preview of the repo, {@code <repo>-pr-<n>.apps.<zone>…}. */
    PREVIEW;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SecretScope parse(String s) {
        try {
            return valueOf(s == null ? "" : s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown secret scope: " + s);
        }
    }
}

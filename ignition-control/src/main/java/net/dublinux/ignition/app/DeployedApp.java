package net.dublinux.ignition.app;

import java.util.Map;

/**
 * One deployed app, unique within its zone. Persisted as
 * {@code state/zones/<slug>/apps/<name>.env}.
 */
public record DeployedApp(
        String zone,
        String name,
        String node,
        String image,
        int port,
        String deployId) {

    public static DeployedApp fromEnv(String zone, String name, Map<String, String> env) {
        int port;
        try {
            port = Integer.parseInt(env.getOrDefault("PORT", "0").strip());
        } catch (NumberFormatException e) {
            port = 0;
        }
        return new DeployedApp(
                zone, name,
                env.getOrDefault("NODE", ""),
                env.getOrDefault("IMAGE", ""),
                port,
                env.getOrDefault("DEPLOY_ID", ""));
    }

    /** {@code <name>.apps.<zone>.<baseDomain>} */
    public String url(String baseDomain) {
        return "https://%s.apps.%s.%s/".formatted(name, zone, baseDomain);
    }
}

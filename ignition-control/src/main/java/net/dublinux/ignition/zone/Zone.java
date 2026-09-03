package net.dublinux.ignition.zone;

import java.util.Map;

/**
 * One team's isolated stack, assigned 1:1 to a node. Persisted as
 * {@code state/zones/<slug>/zone.env}.
 */
public record Zone(
        String slug,
        String node,
        String baseDomain,
        double zoneCpus,
        double zoneMemGb,
        String gitHost,
        String zadminHost,
        String forgejoUrl,
        String zadminUrl,
        String appsBase) {

    public static Zone fromEnv(String slug, Map<String, String> env) {
        return new Zone(
                slug,
                env.getOrDefault("NODE", ""),
                env.getOrDefault("BASE_DOMAIN", ""),
                parseDouble(env.get("ZONE_CPUS")),
                parseDouble(env.get("ZONE_MEM_GB")),
                env.getOrDefault("GIT_HOST", ""),
                env.getOrDefault("ZADMIN_HOST", ""),
                env.getOrDefault("FORGEJO_URL", ""),
                env.getOrDefault("ZADMIN_URL", ""),
                env.getOrDefault("APPS_BASE", ""));
    }

    private static double parseDouble(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

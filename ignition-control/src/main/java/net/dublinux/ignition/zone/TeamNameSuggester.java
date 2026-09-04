package net.dublinux.ignition.zone;

import java.security.SecureRandom;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Suggests a two-word {@code adjective-animal} slug for a new team — the
 * same style as every example slug already scattered through this codebase's
 * own docs and placeholders ({@code quantum-badgers}, {@code pixel-foxes},
 * {@code neon-yaks}). Checked against existing zones before being offered,
 * so what you see is always actually free to use.
 */
@Component
public class TeamNameSuggester {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final List<String> ADJECTIVES = List.of(
            "quantum", "pixel", "neon", "cosmic", "cyber", "electric", "mystic", "turbo",
            "atomic", "stealth", "frozen", "golden", "crimson", "lunar", "solar", "rapid",
            "silent", "wild", "ancient", "rogue", "velvet", "arctic", "blazing", "shadow");

    private static final List<String> ANIMALS = List.of(
            "badgers", "foxes", "yaks", "wolves", "otters", "falcons", "ravens", "tigers",
            "pandas", "dolphins", "eagles", "lynxes", "herons", "martens", "bison", "hornets",
            "cobras", "panthers", "orcas", "sparrows", "mongooses", "wombats", "narwhals", "hedgehogs");

    private final ZoneService zones;

    public TeamNameSuggester(ZoneService zones) {
        this.zones = zones;
    }

    /** A display name ({@code "Hairy Badgers"}) alongside the slug it maps to ({@code "hairy-badgers"}). */
    public record NameSuggestion(String name, String slug) {}

    /** A free name/slug pair — tries a random adjective-animal pair a few times, then falls back to a numbered one. */
    public NameSuggestion suggest() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String adjective = pick(ADJECTIVES);
            String animal = pick(ANIMALS);
            String slug = adjective + "-" + animal;
            if (zones.get(slug).isEmpty()) {
                return new NameSuggestion(capitalize(adjective) + " " + capitalize(animal), slug);
            }
        }
        // Every combination above is taken (2,300+ pairs — vanishingly unlikely,
        // but a real event could plausibly run this generator that many times).
        for (int suffix = 2; suffix < 100; suffix++) {
            String adjective = pick(ADJECTIVES);
            String animal = pick(ANIMALS);
            String slug = adjective + "-" + animal + "-" + suffix;
            if (zones.get(slug).isEmpty()) {
                return new NameSuggestion(capitalize(adjective) + " " + capitalize(animal) + " " + suffix, slug);
            }
        }
        throw new IllegalStateException("could not find a free team name — this shouldn't happen");
    }

    private static String pick(List<String> words) {
        return words.get(RANDOM.nextInt(words.size()));
    }

    private static String capitalize(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    /** Derives a slug the same way the form's own JS does, for names typed or edited by hand. */
    public static String slugify(String name) {
        return name.strip().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }
}

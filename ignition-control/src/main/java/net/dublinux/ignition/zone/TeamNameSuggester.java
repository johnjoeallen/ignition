package net.dublinux.ignition.zone;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Suggests a two-word {@code Modifier Noun} name for a new team ({@code "Hairy
 * Badgers"}), with the matching slug ({@code hairy-badgers}) — the same style
 * as every example slug already scattered through this codebase's own docs
 * and placeholders. Checked against existing zones before being offered, so
 * what you see is always actually free to use.
 */
@Component
public class TeamNameSuggester {

    private static final SecureRandom RANDOM = new SecureRandom();

    // Science / tech
    private static final List<String> SCIENCE_TECH = List.of(
            "Quantum", "Atomic", "Nuclear", "Photon", "Plasma", "Proton", "Neutron", "Electron", "Ionic",
            "Molecular", "Genetic", "Neural", "Synthetic", "Digital", "Binary", "Hexadecimal", "Recursive",
            "Parallel", "Distributed", "Dynamic", "Static", "Elastic", "Virtual", "Augmented", "Autonomous",
            "Robotic", "Cyber", "Nano", "Micro", "Macro", "Hyper", "Meta", "Crypto", "Algorithmic", "Semantic",
            "Vector", "Matrix", "Tensor", "Lambda", "Kernel", "Pixel", "Data", "Cloud", "Edge", "API", "Runtime",
            "Compile", "Reactive", "Asynchronous", "Concurrent", "Modular", "Fractal", "Chaotic", "Entropic",
            "Kinetic", "Magnetic", "Electric", "Optical", "Sonic", "Thermal", "Cosmic", "Orbital", "Galactic",
            "Stellar", "Lunar", "Solar", "Martian", "Jovian", "Astral", "Interstellar", "Temporal", "Spatial",
            "Relativistic", "Infinite", "Zero", "Prime", "Random", "Deterministic", "Bayesian", "Fuzzy",
            "Boolean", "Analog", "Hybrid", "Streaming", "Persistent", "Stateless", "Stateful", "Cached",
            "Indexed", "Sharded", "Federated", "Optimistic", "Speculative");

    // Energy / movement
    private static final List<String> ENERGY_MOVEMENT = List.of(
            "Turbo", "Rapid", "Swift", "Flying", "Racing", "Charging", "Blazing", "Flaming", "Burning",
            "Sparking", "Thundering", "Rolling", "Roaring", "Soaring", "Diving", "Leaping", "Jumping",
            "Bounding", "Spinning", "Drifting", "Gliding", "Sliding", "Zooming", "Accelerating", "Supersonic",
            "Hypersonic", "Rocket", "Jet", "Warp", "Hyperdrive", "Velocity", "Momentum", "Agile", "Nimble",
            "Fleet", "Unstoppable", "Relentless", "Restless");

    // Personality / attitude
    private static final List<String> PERSONALITY = List.of(
            "Brave", "Bold", "Clever", "Curious", "Sneaky", "Sly", "Cunning", "Wise", "Mighty", "Fearless",
            "Fierce", "Wild", "Rogue", "Rebel", "Maverick", "Renegade", "Noble", "Gallant", "Heroic", "Epic",
            "Legendary", "Brilliant", "Bright", "Sharp", "Crafty", "Witty", "Cheeky", "Happy", "Grumpy",
            "Angry", "Hungry", "Sleepy", "Noisy", "Silent", "Loud", "Quiet", "Shy", "Friendly", "Dangerous",
            "Reckless", "Lucky", "Unlucky", "Peculiar", "Odd", "Strange", "Weird", "Eccentric", "Magnificent",
            "Glorious", "Fabulous", "Fantastic", "Marvelous", "Excellent", "Supreme", "Ultimate", "Unreasonable");

    // Slightly ridiculous
    private static final List<String> RIDICULOUS = List.of(
            "Hairy", "Fuzzy", "Fluffy", "Scruffy", "Wonky", "Wobbly", "Squishy", "Crispy", "Crunchy", "Toasted",
            "Pickled", "Spicy", "Salty", "Sticky", "Bouncy", "Dizzy", "Caffeinated", "Decaffeinated",
            "Overcaffeinated", "Hangry", "Confused", "Bewildered", "Perplexed", "Suspicious", "Questionable",
            "Dubious", "Accidental", "Unexpected", "Improvised", "Unsupervised", "Unlicensed", "Unfiltered",
            "Unplugged", "Unhinged", "Overclocked", "Underfunded", "Overengineered", "Undocumented",
            "Deprecated", "Experimental", "Unstable", "Untested", "Impossible", "Improbable", "Slightly-Broken");

    // Colour / material
    private static final List<String> COLOUR_MATERIAL = List.of(
            "Red", "Crimson", "Scarlet", "Orange", "Amber", "Golden", "Yellow", "Lime", "Emerald", "Green",
            "Jade", "Cyan", "Azure", "Blue", "Indigo", "Violet", "Purple", "Magenta", "Pink", "Silver", "Grey",
            "Black", "White", "Ivory", "Copper", "Bronze", "Platinum", "Titanium", "Iron", "Steel", "Carbon",
            "Chrome", "Neon", "Velvet", "Glass", "Crystal", "Obsidian", "Granite", "Marble", "Diamond");

    // Weather / nature
    private static final List<String> WEATHER_NATURE = List.of(
            "Stormy", "Thunder", "Lightning", "Rainy", "Misty", "Foggy", "Frosty", "Frozen", "Icy", "Snowy",
            "Windy", "Breezy", "Tidal", "Oceanic", "Volcanic", "Arctic", "Tropical", "Desert", "Alpine",
            "Forest", "Jungle", "Mountain", "River", "Ocean", "Meteoric", "Comet", "Aurora", "Eclipse", "Monsoon");

    // Time / space / frontier
    private static final List<String> TIME_SPACE = List.of(
            "Future", "Retro", "Ancient", "Eternal", "Midnight", "Dawn", "Twilight", "Daybreak", "Horizon",
            "Frontier", "Outer", "Deep", "Far", "Lost", "Hidden", "Secret", "Unknown", "Uncharted", "Remote",
            "Distant", "Last", "First", "Final", "Next", "New", "Old", "Alpha", "Omega");

    private static final List<String> MODIFIERS = dedupe(
            SCIENCE_TECH, ENERGY_MOVEMENT, PERSONALITY, RIDICULOUS, COLOUR_MATERIAL, WEATHER_NATURE, TIME_SPACE);

    // Animals
    private static final List<String> ANIMALS = List.of(
            "Badgers", "Eagles", "Otters", "Foxes", "Wolves", "Ravens", "Owls", "Falcons", "Hawks", "Kestrels",
            "Bears", "Tigers", "Lions", "Leopards", "Panthers", "Jaguars", "Lynxes", "Wildcats", "Bobcats",
            "Coyotes", "Jackals", "Hyenas", "Dingoes", "Sharks", "Dolphins", "Orcas", "Whales", "Narwhals",
            "Seals", "Walruses", "Penguins", "Puffins", "Pelicans", "Albatrosses", "Gulls", "Crows", "Magpies",
            "Robins", "Sparrows", "Swifts", "Swallows", "Herons", "Cranes", "Storks", "Flamingos", "Peacocks",
            "Geese", "Ducks", "Chickens", "Roosters", "Turkeys", "Geckos", "Lizards", "Iguanas", "Chameleons",
            "Crocodiles", "Alligators", "Turtles", "Tortoises", "Frogs", "Toads", "Salamanders", "Newts",
            "Snakes", "Cobras", "Vipers", "Pythons", "Boas", "Mambas", "Ants", "Bees", "Wasps", "Hornets",
            "Beetles", "Moths", "Butterflies", "Dragonflies", "Spiders", "Scorpions", "Centipedes");

    // More amusing animals
    private static final List<String> AMUSING_ANIMALS = List.of(
            "Ferrets", "Hamsters", "Gerbils", "Guinea Pigs", "Alpacas", "Llamas", "Capybaras", "Wombats",
            "Koalas", "Quokkas", "Meerkats", "Raccoons", "Skunks", "Sloths", "Armadillos", "Aardvarks",
            "Anteaters", "Tapirs", "Platypuses", "Possums", "Lemurs", "Monkeys", "Gorillas", "Chimps",
            "Baboons", "Macaques", "Gibbons", "Orangutans", "Moles", "Hedgehogs", "Squirrels", "Chipmunks",
            "Beavers", "Marmots", "Weasels", "Stoats", "Minks");

    // Mythical / fantasy
    private static final List<String> MYTHICAL = List.of(
            "Dragons", "Phoenixes", "Griffins", "Krakens", "Hydras", "Minotaurs", "Cyclopes", "Titans",
            "Giants", "Goblins", "Gremlins", "Trolls", "Elves", "Dwarves", "Wizards", "Sorcerers", "Warlocks",
            "Witches", "Druids", "Paladins", "Rangers", "Rogues", "Knights", "Samurai", "Ninjas", "Pirates",
            "Vikings", "Nomads", "Centaurs", "Gargoyles", "Golems", "Sprites", "Imps", "Fairies", "Djinn",
            "Yetis", "Sasquatches", "Unicorns");

    // People / professions
    private static final List<String> PEOPLE = List.of(
            "Bikers", "Hackers", "Coders", "Builders", "Makers", "Tinkerers", "Inventors", "Engineers",
            "Architects", "Explorers", "Pioneers", "Navigators", "Pilots", "Captains", "Commanders", "Rangers",
            "Scouts", "Miners", "Mechanics", "Drivers", "Riders", "Racers", "Scientists", "Professors",
            "Doctors", "Wizards", "Operators", "Designers", "Dreamers", "Thinkers", "Rebels", "Mavericks",
            "Outlaws", "Bandits", "Pirates", "Nomads", "Mercenaries", "Adventurers", "Travellers", "Astronauts",
            "Cosmonauts", "Alchemists");

    // Machines / objects
    private static final List<String> MACHINES = List.of(
            "Rockets", "Jets", "Engines", "Motors", "Turbines", "Gears", "Pistons", "Bolts", "Sparks",
            "Circuits", "Transistors", "Relays", "Switches", "Routers", "Proxies", "Servers", "Nodes",
            "Clusters", "Kernels", "Threads", "Processes", "Queues", "Buffers", "Streams", "Pipelines",
            "Bridges", "Tunnels", "Gates", "Portals", "Beacons", "Radars", "Lasers", "Satellites", "Drones",
            "Bots", "Robots", "Automata", "Reactors", "Generators", "Batteries", "Capacitors", "Magnets",
            "Vectors", "Matrices", "Tensors", "Pixels", "Voxels", "Tokens", "Packets", "Frames", "Signals",
            "Waves");

    // Space
    private static final List<String> SPACE = List.of(
            "Rockets", "Comets", "Meteors", "Asteroids", "Planets", "Moons", "Stars", "Suns", "Pulsars",
            "Quasars", "Nebulae", "Galaxies", "Supernovas", "Blackholes", "Orbits", "Satellites", "Rovers",
            "Landers", "Voyagers", "Probes", "Shuttles");

    // Abstract / innovation
    private static final List<String> ABSTRACT = List.of(
            "Sparks", "Ideas", "Catalysts", "Signals", "Waves", "Horizons", "Frontiers", "Missions", "Ventures",
            "Experiments", "Prototypes", "Concepts", "Engines", "Accelerators", "Disruptors", "Innovators",
            "Creators", "Dreamers", "Thinkers", "Builders", "Pioneers", "Explorers", "Mavericks", "Outliers",
            "Exceptions", "Variables", "Constants", "Paradoxes", "Loops", "Branches", "Forks", "Threads",
            "Streams");

    private static final List<String> NOUNS = dedupe(
            ANIMALS, AMUSING_ANIMALS, MYTHICAL, PEOPLE, MACHINES, SPACE, ABSTRACT);

    private final ZoneService zones;

    public TeamNameSuggester(ZoneService zones) {
        this.zones = zones;
    }

    /** A display name ({@code "Hairy Badgers"}) alongside the slug it maps to ({@code "hairy-badgers"}). */
    public record NameSuggestion(String name, String slug) {}

    /** A free name/slug pair — tries a random modifier-noun pair a few times, then falls back to a numbered one. */
    public NameSuggestion suggest() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String modifier = pick(MODIFIERS);
            String noun = pick(NOUNS);
            String slug = slugSegment(modifier) + "-" + slugSegment(noun);
            if (zones.get(slug).isEmpty()) {
                return new NameSuggestion(modifier + " " + noun, slug);
            }
        }
        // MODIFIERS x NOUNS is tens of thousands of pairs — vanishingly unlikely
        // to run dry, but a real event could plausibly run this many attempts.
        for (int suffix = 2; suffix < 100; suffix++) {
            String modifier = pick(MODIFIERS);
            String noun = pick(NOUNS);
            String slug = slugSegment(modifier) + "-" + slugSegment(noun) + "-" + suffix;
            if (zones.get(slug).isEmpty()) {
                return new NameSuggestion(modifier + " " + noun + " " + suffix, slug);
            }
        }
        throw new IllegalStateException("could not find a free team name — this shouldn't happen");
    }

    private static String pick(List<String> words) {
        return words.get(RANDOM.nextInt(words.size()));
    }

    private static String slugSegment(String word) {
        return word.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
    }

    @SafeVarargs
    private static List<String> dedupe(List<String>... lists) {
        Set<String> seen = new LinkedHashSet<>();
        for (List<String> list : lists) {
            seen.addAll(list);
        }
        return List.copyOf(seen);
    }

    /** Derives a slug the same way the form's own JS does, for names typed or edited by hand. */
    public static String slugify(String name) {
        return name.strip().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }
}

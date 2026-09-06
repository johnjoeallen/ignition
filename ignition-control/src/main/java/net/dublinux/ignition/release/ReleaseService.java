package net.dublinux.ignition.release;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import net.dublinux.ignition.forgejo.ForgejoClient;
import org.springframework.stereotype.Service;

/**
 * Cuts a release by tagging the next version on {@code main} through the
 * Forgejo API — no local git, no Releases form. Mirrors {@code cut_release()}
 * / {@code classify_bump()} in {@code ign-control.py}.
 */
@Service
public class ReleaseService {

    private static final Pattern SEMVER = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$");
    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern CONV_MAJOR = Pattern.compile("^[a-zA-Z]+(\\([^)]*\\))?!:");
    private static final Pattern CONV_FEAT = Pattern.compile("^feat(\\([^)]*\\))?:", Pattern.CASE_INSENSITIVE);

    private final ForgejoClient forgejo;

    public ReleaseService(ForgejoClient forgejo) {
        this.forgejo = forgejo;
    }

    /** First line of a Forgejo merge-commit message: {@code Merge pull request 'Title' (#12) from …}. */
    private static final Pattern MERGE_PR = Pattern.compile("Merge pull request '(.+)' \\(#(\\d+)\\)");

    /** {@code (status, forgejo body message, tag, resolved kind)} */
    public record Result(int status, String message, String tag, String kind) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** A merged PR sitting on {@code main} that no release tag covers yet. */
    public record PendingPr(int number, String title) {}

    /**
     * What's on {@code main} that the latest release doesn't have — so the app
     * page can say "you have unreleased work" instead of leaving the team to
     * notice a merge did nothing.
     *
     * @param everReleased   false if the repo has no {@code vX.Y.Z} tag yet
     * @param lastTag        the latest release tag, or {@code "—"} if none
     * @param lastReleaseAt  when that release was cut (for the closed-issue
     *                       filter), or {@code null}
     * @param commitCount    commits on {@code main} since {@code lastTag}
     * @param prs            merged PRs among those commits (best-effort parse)
     * @param suggestedBump  {@code major}/{@code minor}/{@code patch} from
     *                       Conventional Commits over those messages
     */
    public record Pending(boolean everReleased, String lastTag, java.time.Instant lastReleaseAt,
                          int commitCount, List<PendingPr> prs, String suggestedBump) {
        public boolean hasWork() {
            return commitCount > 0;
        }
    }

    public Pending pending(String slug, String owner, String repo) {
        if (!REPO.matcher(owner).matches() || !REPO.matcher(repo).matches()) {
            throw new IllegalArgumentException("bad owner/repo");
        }
        int[] cur = latestSemver(forgejo.get(slug, "/repos/%s/%s/tags?limit=50".formatted(owner, repo)).body());
        boolean everReleased = !isZero(cur);
        String lastTag = everReleased ? "v%d.%d.%d".formatted(cur[0], cur[1], cur[2]) : "—";

        List<String> messages = commitsSince(slug, owner, repo, everReleased ? lastTag : "");
        List<PendingPr> prs = new ArrayList<>();
        for (String m : messages) {
            Matcher mm = MERGE_PR.matcher(m.lines().findFirst().orElse(""));
            if (mm.find()) {
                prs.add(new PendingPr(Integer.parseInt(mm.group(2)), mm.group(1)));
            }
        }
        return new Pending(everReleased, lastTag, latestReleaseInstant(slug, owner, repo),
                messages.size(), prs, classifyBump(messages));
    }

    private java.time.Instant latestReleaseInstant(String slug, String owner, String repo) {
        JsonNode body = forgejo.get(slug,
                "/repos/%s/%s/releases?limit=1".formatted(owner, repo)).body();
        if (body != null && body.isArray() && !body.isEmpty()) {
            String at = body.get(0).path("published_at").asText(body.get(0).path("created_at").asText(""));
            try {
                return at.isBlank() ? null : java.time.Instant.parse(at);
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    public Result cut(String slug, String owner, String repo, String kind) {
        if (!REPO.matcher(owner).matches() || !REPO.matcher(repo).matches()) {
            throw new IllegalArgumentException("bad owner/repo");
        }
        int[] cur = latestSemver(forgejo.get(slug, "/repos/%s/%s/tags?limit=50".formatted(owner, repo)).body());
        boolean auto = !List.of("patch", "minor", "major").contains(kind);
        String base = isZero(cur) ? "" : "v%d.%d.%d".formatted(cur[0], cur[1], cur[2]);
        String resolved = auto ? classifyBump(commitsSince(slug, owner, repo, base)) : kind;

        String tag = isZero(cur)
                ? ("major".equals(resolved) ? "v1.0.0" : "v0.1.0")
                : bump(cur, resolved);
        String label = auto ? resolved + ", from commits" : resolved + ", manual";

        ForgejoClient.Response res = forgejo.post(slug,
                "/repos/%s/%s/releases".formatted(owner, repo),
                Map.of("tag_name", tag, "target_commitish", "main", "name", tag,
                        "body", "Released from the zone console (" + label + ")."));
        return new Result(res.status(), res.message(), tag, resolved);
    }

    // --- semver ----------------------------------------------------------------

    public static int[] latestSemver(JsonNode tags) {
        int[] best = {0, 0, 0};
        if (tags != null && tags.isArray()) {
            for (JsonNode t : tags) {
                Matcher m = SEMVER.matcher(t.path("name").asText(""));
                if (m.matches()) {
                    int[] v = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
                    if (compare(v, best) > 0) {
                        best = v;
                    }
                }
            }
        }
        return best;
    }

    public static String bump(int[] v, String kind) {
        int maj = v[0], mnr = v[1], pat = v[2];
        switch (kind) {
            case "major" -> { maj++; mnr = 0; pat = 0; }
            case "minor" -> { mnr++; pat = 0; }
            default -> pat++;
        }
        return "v%d.%d.%d".formatted(maj, mnr, pat);
    }

    public static String classifyBump(List<String> messages) {
        String bump = "patch";
        for (String msg : messages) {
            String head = msg.lines().findFirst().orElse("").strip();
            if (msg.contains("BREAKING CHANGE") || CONV_MAJOR.matcher(head).find()) {
                return "major";
            }
            if (CONV_FEAT.matcher(head).find()) {
                bump = "minor";
            }
        }
        return bump;
    }

    private List<String> commitsSince(String slug, String owner, String repo, String baseTag) {
        List<String> out = new ArrayList<>();
        if (!baseTag.isBlank()) {
            JsonNode body = forgejo.get(slug,
                    "/repos/%s/%s/compare/%s...main".formatted(owner, repo, baseTag)).body();
            JsonNode commits = body == null ? null : body.get("commits");
            if (commits != null && commits.isArray()) {
                commits.forEach(c -> out.add(c.path("commit").path("message").asText("")));
                return out;
            }
        }
        JsonNode body = forgejo.get(slug,
                "/repos/%s/%s/commits?sha=main&limit=50".formatted(owner, repo)).body();
        if (body != null && body.isArray()) {
            body.forEach(c -> out.add(c.path("commit").path("message").asText("")));
        }
        return out;
    }

    private static boolean isZero(int[] v) {
        return v[0] == 0 && v[1] == 0 && v[2] == 0;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }
}

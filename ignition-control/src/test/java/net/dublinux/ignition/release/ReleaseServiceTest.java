package net.dublinux.ignition.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReleaseServiceTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void latestSemverPicksTheHighest() {
        var tags = json.createArrayNode();
        tags.addObject().put("name", "v1.2.3");
        tags.addObject().put("name", "v1.10.0");
        tags.addObject().put("name", "nope");
        tags.addObject().put("name", "0.9.0");
        assertThat(ReleaseService.latestSemver(tags)).containsExactly(1, 10, 0);
    }

    @Test
    void latestSemverDefaultsToZero() {
        assertThat(ReleaseService.latestSemver(json.createArrayNode())).containsExactly(0, 0, 0);
        assertThat(ReleaseService.latestSemver(null)).containsExactly(0, 0, 0);
    }

    @Test
    void bumpFollowsSemver() {
        assertThat(ReleaseService.bump(new int[]{1, 10, 0}, "patch")).isEqualTo("v1.10.1");
        assertThat(ReleaseService.bump(new int[]{1, 10, 0}, "minor")).isEqualTo("v1.11.0");
        assertThat(ReleaseService.bump(new int[]{1, 10, 0}, "major")).isEqualTo("v2.0.0");
    }

    @Test
    void classifyBumpFromConventionalCommits() {
        assertThat(ReleaseService.classifyBump(List.of("fix: a bug", "docs: readme"))).isEqualTo("patch");
        assertThat(ReleaseService.classifyBump(List.of("feat: new thing", "fix: x"))).isEqualTo("minor");
        assertThat(ReleaseService.classifyBump(List.of("feat!: drop old api"))).isEqualTo("major");
        assertThat(ReleaseService.classifyBump(List.of("refactor: x\n\nBREAKING CHANGE: gone"))).isEqualTo("major");
        assertThat(ReleaseService.classifyBump(List.of("chore: bump deps"))).isEqualTo("patch");
        assertThat(ReleaseService.classifyBump(List.of("feat(ui): button", "feat: api"))).isEqualTo("minor");
    }
}

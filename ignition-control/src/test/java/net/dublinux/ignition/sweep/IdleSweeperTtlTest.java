package net.dublinux.ignition.sweep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class IdleSweeperTtlTest {

    @Test
    void aPrPreviewFollowsItsReposIdleTime() {
        Map<String, Integer> ttls = Map.of("cardart", 60);
        assertEquals(60, IdleSweeper.ttlFor(ttls, "cardart"));
        assertEquals(60, IdleSweeper.ttlFor(ttls, "cardart-pr-4"));   // preview inherits the repo's
        assertNull(IdleSweeper.ttlFor(ttls, "other"));                // no setting -> platform default
        assertNull(IdleSweeper.ttlFor(ttls, "other-pr-4"));
    }

    @Test
    void neverAndUnsetAreKeptApart() {
        Map<String, Integer> ttls = new HashMap<>();
        ttls.put("cardart", 0);        // never stop
        ttls.put("blank", null);       // row exists, default
        assertEquals(0, IdleSweeper.ttlFor(ttls, "cardart-pr-9"));
        assertNull(IdleSweeper.ttlFor(ttls, "blank-pr-9"));
    }

    @Test
    void aReposOwnSettingWinsOverItsNameLookingLikeAPreview() {
        Map<String, Integer> ttls = Map.of("odd-pr-3", 120, "odd", 60);
        assertEquals(120, IdleSweeper.ttlFor(ttls, "odd-pr-3"));
    }
}

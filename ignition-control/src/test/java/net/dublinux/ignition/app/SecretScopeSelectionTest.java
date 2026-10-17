package net.dublinux.ignition.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SecretScopeSelectionTest {

    @Test
    void eachDeploymentGetsItsOwnEnvironmentsSecrets() {
        assertEquals(SecretScope.RELEASE, AppService.secretScope(Channel.RELEASE, "shop", "shop"));
        assertEquals(SecretScope.DEV, AppService.secretScope(Channel.DEV, "shop", "shop"));
        // a PR preview deploys as <repo>-pr-<n> from the <repo> repo
        assertEquals(SecretScope.PREVIEW, AppService.secretScope(Channel.RELEASE, "shop-pr-42", "shop"));
    }

    @Test
    void parsesScopeNamesAndRejectsUnknownOnes() {
        assertEquals(SecretScope.PREVIEW, SecretScope.parse(" preview "));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> SecretScope.parse("prod"));
    }
}

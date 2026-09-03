package net.dublinux.ignition.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProvisioningServiceTest {

    @Test
    void forgejoUuidMatchesTheShellDerivation() {
        // first 16 chars of the secret, as ASCII bytes, hex-encoded, 8-4-4-4-12
        assertThat(ProvisioningService.forgejoUuid("0123456789abcdef" + "0".repeat(24)))
                .isEqualTo("30313233-3435-3637-3839-616263646566");
    }
}

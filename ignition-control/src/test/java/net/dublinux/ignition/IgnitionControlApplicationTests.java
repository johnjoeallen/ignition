package net.dublinux.ignition;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "ignition.admin-token=test-platform-token",
        "ignition.state-dir=target/test-state"
})
class IgnitionControlApplicationTests {

    @Test
    void contextLoads() {
    }
}

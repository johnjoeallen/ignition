package net.dublinux.ignition;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Full-context smoke test — boots the app against a real PostgreSQL (Flyway runs,
 * every bean wires). Skipped where there is no usable Docker.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "ignition.admin-token=test-platform-token",
        "ignition.secret-key=/DoDwvqamAc1dBkxMs9k7J3mrLX1ORbse5AK1Z2Sa/k=",
        "ignition.work-dir=target/test-work",
        "ignition.smtp.host=localhost",
        "ignition.smtp.username=ignition",
        "ignition.smtp.password=test",
        "ignition.smtp.from=Ignition <ignition@example.com>"
})
class IgnitionControlApplicationTests {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void startPostgres() {
        try {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "no usable Docker environment");
        } catch (RuntimeException e) {
            Assumptions.abort("no usable Docker environment: " + e.getMessage());
        }
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void contextLoads() {
    }
}

package net.dublinux.ignition;

import net.dublinux.ignition.auth.SmtpProperties;
import net.dublinux.ignition.config.IgnitionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ignition control plane.
 *
 * <p>One service, two web consoles: the platform admin manages nodes and zones,
 * a zone admin manages their own zone. It also carries the CI {@code /deploy}
 * bridge. See {@code DESIGN.md} in the repo root.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({IgnitionProperties.class, SmtpProperties.class})
public class IgnitionControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(IgnitionControlApplication.class, args);
    }
}

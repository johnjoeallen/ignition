package net.dublinux.ignition.auth;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * SMTP settings for activation / approval / reset mail. <b>Required</b> — a
 * missing value is a startup failure, by design (AUTH-DESIGN "SMTP required").
 */
@ConfigurationProperties(prefix = "ignition.smtp")
@Validated
public class SmtpProperties {

    @NotBlank
    private String host = "";

    private int port = 587;

    @NotBlank
    private String username = "";

    @NotBlank
    private String password = "";

    /** Envelope / header From, e.g. {@code "Ignition <ignition@example.com>"}. */
    @NotBlank
    private String from = "";

    private boolean starttls = true;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public boolean isStarttls() { return starttls; }
    public void setStarttls(boolean starttls) { this.starttls = starttls; }
}

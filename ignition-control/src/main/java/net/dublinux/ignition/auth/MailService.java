package net.dublinux.ignition.auth;

import java.util.Properties;

import net.dublinux.ignition.config.IgnitionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

/**
 * Sends every transactional mail (activation link, password reset, team
 * add/remove). The {@link JavaMailSender} is built
 * straight from {@link SmtpProperties} so the one config surface is the
 * {@code ignition.smtp.*} block.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mail;
    private final SmtpProperties smtp;
    private final IgnitionProperties props;

    public MailService(JavaMailSender mail, SmtpProperties smtp, IgnitionProperties props) {
        this.mail = mail;
        this.smtp = smtp;
        this.props = props;
    }

    public void sendActivation(String email, String rawToken) {
        String link = props.getPublicUrl() + "/activate?token=" + rawToken;
        send(email, "Activate your Ignition account", """
                Someone (probably you) asked to create an Ignition account for this address.

                Set your password to finish:
                %s

                The link is valid for 24 hours. If this wasn't you, ignore this mail.
                """.formatted(link));
    }

    public void sendAddedToTeam(String email, String slug, String role) {
        String link = props.getPublicUrl().replaceAll("/+$", "") + "/teams/" + slug;
        send(email, "You've been added to " + slug, """
                You've been added to the "%s" team on Ignition, as %s.

                Open the team's console:
                %s

                Your own git password and personal access token are shown there,
                on your own row — nobody else's, including other admins, are shown.
                """.formatted(slug, role, link));
    }

    public void sendRemovedFromTeam(String email, String slug) {
        send(email, "You've been removed from " + slug, """
                You've been removed from the "%s" team on Ignition. Your git access
                to that team (login, password, personal access token) was removed
                along with it.

                If this wasn't expected, ask a "%s" team admin, or a platform admin.
                """.formatted(slug, slug));
    }

    public void sendReset(String email, String rawToken) {
        String link = props.getPublicUrl() + "/reset?token=" + rawToken;
        send(email, "Reset your Ignition password", """
                A password reset was requested for this address.

                Choose a new password:
                %s

                The link is valid for 1 hour. If this wasn't you, ignore this mail.
                """.formatted(link));
    }

    private void send(String to, String subject, String body) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(smtp.getFrom());
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        try {
            mail.send(msg);
            log.info("sent '{}' to {}", subject, to);
        } catch (RuntimeException e) {
            // Don't lose the link if SMTP is misconfigured — an operator can
            // still recover it from the logs.
            log.warn("SMTP send FAILED for '{}' to {} ({}). Link/body follows:\n{}",
                    subject, to, e.getMessage(), body);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MailConfig {

        @Bean
        JavaMailSender javaMailSender(SmtpProperties smtp) {
            JavaMailSenderImpl impl = new JavaMailSenderImpl();
            impl.setHost(smtp.getHost());
            impl.setPort(smtp.getPort());
            impl.setUsername(smtp.getUsername());
            impl.setPassword(smtp.getPassword());
            Properties p = impl.getJavaMailProperties();
            p.put("mail.transport.protocol", "smtp");
            p.put("mail.smtp.auth", "true");
            p.put("mail.smtp.starttls.enable", Boolean.toString(smtp.isStarttls()));
            return impl;
        }
    }
}

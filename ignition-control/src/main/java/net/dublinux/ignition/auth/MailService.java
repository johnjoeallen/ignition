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
 * Sends the three transactional mails. The {@link JavaMailSender} is built
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

    public void sendApproved(String email) {
        send(email, "Your Ignition account is approved", """
                A platform admin has approved your Ignition account.

                Sign in: %s/login
                """.formatted(props.getPublicUrl()));
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

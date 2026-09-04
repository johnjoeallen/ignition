package net.dublinux.ignition.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

import net.dublinux.ignition.config.IgnitionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * First-run bootstrap. While {@code app_user} is empty, a random code is
 * generated and logged; {@code /setup} accepts that code plus an email and
 * password to create the first platform admin. Once any user exists, the code
 * and {@code /setup} are dead.
 */
@Component
public class BootstrapService {

    private static final Logger log = LoggerFactory.getLogger(BootstrapService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountService accounts;
    private final IgnitionProperties props;
    private volatile String code;

    public BootstrapService(AccountService accounts, IgnitionProperties props) {
        this.accounts = accounts;
        this.props = props;
    }

    public boolean pending() {
        return accounts.userCount() == 0;
    }

    public boolean codeMatches(String supplied) {
        String c = code;
        return c != null && supplied != null
                && MessageDigest.isEqual(c.getBytes(StandardCharsets.UTF_8), supplied.strip().getBytes(StandardCharsets.UTF_8));
    }

    /** Runs at startup and every 30s: (re)announce the code while setup is pending. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 2_000)
    void announce() {
        if (!pending()) {
            code = null;
            return;
        }
        if (code == null) {
            byte[] b = new byte[12];
            RANDOM.nextBytes(b);
            code = HexFormat.of().formatHex(b);
        }
        log.warn("IGNITION SETUP — open {}/setup and enter code: {}", props.getPublicUrl(), code);
    }
}

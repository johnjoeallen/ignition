package net.dublinux.ignition.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import net.dublinux.ignition.auth.AppUser.Status;
import net.dublinux.ignition.auth.AuthToken.Purpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts: signup, email-verified activation, admin invite, approval, disable,
 * password reset, and the platform-admin flag. No authorization here — callers
 * (controllers, in AUTH-DESIGN step 4+) enforce who may invoke what.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PASSWORD = Pattern.compile("^.{10,200}$");
    private static final Duration ACTIVATE_TTL = Duration.ofHours(24);
    private static final Duration RESET_TTL = Duration.ofHours(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository users;
    private final AuthTokenRepository tokens;
    private final MailService mail;
    private final PasswordEncoder passwords;

    public AccountService(AppUserRepository users, AuthTokenRepository tokens,
                          MailService mail, PasswordEncoder passwords) {
        this.users = users;
        this.tokens = tokens;
        this.mail = mail;
        this.passwords = passwords;
    }

    public long userCount() {
        return users.count();
    }

    public Optional<AppUser> byEmail(String email) {
        return users.findByEmailIgnoreCase(norm(email));
    }

    public List<AppUser> pendingApproval() {
        return users.findByStatusOrderByCreatedAt(Status.PENDING_APPROVAL);
    }

    // --- signup / invite -----------------------------------------------------

    /** Self-service. Silent whether or not the address is new (no enumeration). */
    @Transactional
    public void signup(String email) {
        String e = requireEmail(email);
        if (users.existsByEmailIgnoreCase(e)) {
            log.info("signup for existing address {} — no mail", e);
            return;
        }
        AppUser u = users.save(new AppUser(e, Status.PENDING_VERIFICATION, false, false));
        mail.sendActivation(e, issue(u.id(), Purpose.ACTIVATE, ACTIVATE_TTL));
    }

    /**
     * Admin-initiated. Returns the (possibly pre-existing) user. A new user is
     * preapproved, so activation lands them straight in ACTIVE.
     */
    @Transactional
    public AppUser invite(String email) {
        String e = requireEmail(email);
        return users.findByEmailIgnoreCase(e).orElseGet(() -> {
            AppUser u = users.save(new AppUser(e, Status.PENDING_VERIFICATION, false, true));
            mail.sendActivation(e, issue(u.id(), Purpose.ACTIVATE, ACTIVATE_TTL));
            return u;
        });
    }

    /**
     * First-run: create the platform admin directly, ACTIVE, with the password
     * set now. No email round-trip — control of the box was already proven by
     * the bootstrap code the operator read from the logs.
     */
    @Transactional
    public AppUser createFirstAdmin(String email, String rawPassword) {
        if (users.count() != 0) {
            throw new IllegalStateException("setup is already done");
        }
        requirePassword(rawPassword);
        String e = requireEmail(email);
        AppUser u = new AppUser(e, Status.ACTIVE, true, true);
        u.activate(passwords.encode(rawPassword));
        return users.save(u);
    }

    // --- activation / reset ------------------------------------------------

    @Transactional
    public AppUser activate(String rawToken, String rawPassword) {
        requirePassword(rawPassword);
        AppUser u = consume(rawToken, Purpose.ACTIVATE);
        if (u.status() == Status.DISABLED) {
            throw new IllegalStateException("account is disabled");
        }
        u.activate(passwords.encode(rawPassword));
        users.save(u);
        tokens.deleteByUserIdAndPurpose(u.id(), Purpose.ACTIVATE);
        log.info("activated {} -> {}", u.email(), u.status());
        return u;
    }

    @Transactional
    public void requestReset(String email) {
        String e = norm(email);
        users.findByEmailIgnoreCase(e).ifPresent(u -> {
            if (u.passwordHash() != null && u.status() != Status.DISABLED) {
                mail.sendReset(e, issue(u.id(), Purpose.RESET, RESET_TTL));
            }
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String rawPassword) {
        requirePassword(rawPassword);
        AppUser u = consume(rawToken, Purpose.RESET);
        u.setPasswordHash(passwords.encode(rawPassword));
        users.save(u);
        tokens.deleteByUserIdAndPurpose(u.id(), Purpose.RESET);
        log.info("password reset for {}", u.email());
    }

    // --- admin actions ---------------------------------------------------

    @Transactional
    public void approve(UUID userId) {
        AppUser u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("no such user"));
        if (u.status() != Status.PENDING_APPROVAL) {
            throw new IllegalStateException("user is not pending approval");
        }
        u.setStatus(Status.ACTIVE);
        users.save(u);
        mail.sendApproved(u.email());
    }

    @Transactional
    public void setDisabled(UUID userId, boolean disabled) {
        AppUser u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("no such user"));
        if (disabled && u.isPlatformAdmin()) {
            guardLastPlatformAdmin(userId);
        }
        u.setStatus(disabled ? Status.DISABLED
                : (u.activatedAt() == null ? Status.PENDING_VERIFICATION : Status.ACTIVE));
        users.save(u);
    }

    @Transactional
    public void setPlatformAdmin(UUID userId, boolean value) {
        AppUser u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("no such user"));
        if (!value && u.isPlatformAdmin()) {
            guardLastPlatformAdmin(userId);
        }
        if (value && u.status() != Status.ACTIVE) {
            throw new IllegalStateException("only an ACTIVE user can be made a platform admin");
        }
        u.setPlatformAdmin(value);
        users.save(u);
    }

    private void guardLastPlatformAdmin(UUID exceptId) {
        long others = users.findAll().stream()
                .filter(x -> x.isPlatformAdmin() && x.status() == Status.ACTIVE && !x.id().equals(exceptId))
                .count();
        if (others == 0) {
            throw new IllegalStateException("cannot remove the last platform admin");
        }
    }

    // --- tokens ---------------------------------------------------------

    private String issue(UUID userId, Purpose purpose, Duration ttl) {
        tokens.deleteByUserIdAndPurpose(userId, purpose);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.save(new AuthToken(sha256(rawToken), userId, purpose, Instant.now().plus(ttl)));
        return rawToken;
    }

    private AppUser consume(String rawToken, Purpose purpose) {
        AuthToken t = tokens.findById(sha256(rawToken == null ? "" : rawToken))
                .filter(x -> x.purpose() == purpose && x.isUsable(Instant.now()))
                .orElseThrow(() -> new IllegalArgumentException("link is invalid or expired"));
        t.markUsed();
        tokens.save(t);
        return users.findById(t.userId())
                .orElseThrow(() -> new IllegalStateException("token has no user"));
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --- validation ---------------------------------------------------

    private static String norm(String email) {
        return email == null ? "" : email.strip().toLowerCase();
    }

    private static String requireEmail(String email) {
        String e = norm(email);
        if (!EMAIL.matcher(e).matches()) {
            throw new IllegalArgumentException("not a valid email address");
        }
        return e;
    }

    private static void requirePassword(String pw) {
        if (pw == null || !PASSWORD.matcher(pw).matches()) {
            throw new IllegalArgumentException("password must be 10–200 characters");
        }
    }
}

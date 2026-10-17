package net.dublinux.ignition.app;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import net.dublinux.ignition.zone.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Write-only app secrets, kept separately for each environment ({@link SecretScope}). The console can list names and when they changed, set
 * and delete them — never read a value back. {@link #forApp} (decrypted) is for
 * the deploy path only. Values are never logged.
 */
@Service
public class AppSecretService {

    static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    static final int MAX_VALUE = 8192;
    static final int MAX_SECRETS = 50;

    private final AppSecretRepository repo;
    private final SecretCipher cipher;
    private final JdbcTemplate jdbc;

    public AppSecretService(AppSecretRepository repo, SecretCipher cipher, JdbcTemplate jdbc) {
        this.repo = repo;
        this.cipher = cipher;
        this.jdbc = jdbc;
    }

    /** When this environment's secrets last changed (a save or a delete), if ever. */
    public Optional<Instant> changedAt(String zone, String app, SecretScope scope) {
        return jdbc.query("select changed_at from app_secret_change where zone_slug = ? and app = ? and scope = ?",
                (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant(), zone, app, scope.key())
                .stream().findFirst();
    }

    private void markChanged(String zone, String app, SecretScope scope) {
        jdbc.update("insert into app_secret_change (zone_slug, app, scope, changed_at) values (?, ?, ?, now()) "
                + "on conflict (zone_slug, app, scope) do update set changed_at = now()", zone, app, scope.key());
    }

    /** Names and metadata only — no values. */
    public List<AppSecret> list(String zone, String app, SecretScope scope) {
        return repo.findByZoneAndAppAndScopeOrderByName(zone, app, scope.key());
    }

    public void put(String zone, String app, SecretScope scope, String name, String value, String by) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a secret name is letters, digits and _ (not starting with a digit), up to 64");
        }
        if (name.equals("PORT")) {
            throw new IllegalArgumentException("PORT is controlled by the platform");
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("a secret needs a value");
        }
        if (value.length() > MAX_VALUE) {
            throw new IllegalArgumentException("a secret value is at most " + MAX_VALUE + " characters");
        }
        var key = new AppSecret.Key(zone, app, scope, name);
        var existing = repo.findById(key);
        if (existing.isEmpty() && list(zone, app, scope).size() >= MAX_SECRETS) {
            throw new IllegalArgumentException("at most " + MAX_SECRETS + " secrets per environment");
        }
        String enc = cipher.encrypt(value);
        if (existing.isPresent()) {
            existing.get().set(enc, by);
            repo.save(existing.get());
        } else {
            repo.save(new AppSecret(zone, app, scope, name, enc, by));
        }
        markChanged(zone, app, scope);
    }

    public void delete(String zone, String app, SecretScope scope, String name) {
        repo.deleteById(new AppSecret.Key(zone, app, scope, name));
        markChanged(zone, app, scope);
    }

    public void deleteAll(String zone, String app) {
        repo.deleteByZoneAndApp(zone, app);
    }

    /** Decrypted {@code NAME -> value}, for merging into a deploy. Never expose this to a page. */
    public Map<String, String> forApp(String zone, String app, SecretScope scope) {
        Map<String, String> out = new LinkedHashMap<>();
        for (AppSecret s : list(zone, app, scope)) {
            out.put(s.name(), cipher.decrypt(s.value()));
        }
        return out;
    }
}

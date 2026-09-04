package net.dublinux.ignition.zone;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Zone persistence facade — the {@code zone} table plus its encrypted
 * {@code zone_secret} rows. Services depend on this, not on Spring Data
 * directly, so the storage move stayed a small diff.
 */
@Component
public class ZoneRepository {

    private static final Logger log = LoggerFactory.getLogger(ZoneRepository.class);

    private final ZoneEntityRepository zoneRepo;
    private final ZoneSecretRepository secretRepo;
    private final SecretCipher cipher;
    private final UserSecretCipher userCipher;

    public ZoneRepository(ZoneEntityRepository zoneRepo, ZoneSecretRepository secretRepo, SecretCipher cipher,
                          UserSecretCipher userCipher) {
        this.zoneRepo = zoneRepo;
        this.secretRepo = secretRepo;
        this.cipher = cipher;
        this.userCipher = userCipher;
    }

    public List<Zone> findAll() {
        return zoneRepo.findAll(Sort.by("slug"));
    }

    public Optional<Zone> find(String slug) {
        return zoneRepo.findById(slug);
    }

    public boolean exists(String slug) {
        return zoneRepo.existsById(slug);
    }

    public Zone save(Zone zone) {
        return zoneRepo.save(zone);
    }

    /** Removes the zone row; {@code zone_secret} and {@code app} rows cascade. */
    @Transactional
    public void delete(String slug) {
        zoneRepo.deleteById(slug);
    }

    // --- secrets ---------------------------------------------------------------

    /** Decrypted secret, or {@code ""} if unset. */
    public String secret(String slug, String name) {
        return secretRepo.findByZoneSlugAndName(slug, name)
                .map(s -> cipher.decrypt(s.value()))
                .orElse("");
    }

    @Transactional
    public void putSecret(String slug, String name, String plaintext) {
        String ct = cipher.encrypt(plaintext);
        secretRepo.findByZoneSlugAndName(slug, name).ifPresentOrElse(
                s -> {
                    s.setValue(ct);
                    secretRepo.save(s);
                },
                () -> secretRepo.save(new ZoneSecret(slug, name, ct)));
    }

    public boolean hasSecret(String slug, String name) {
        return secretRepo.findByZoneSlugAndName(slug, name).isPresent();
    }

    @Transactional
    public void deleteSecret(String slug, String name) {
        secretRepo.findByZoneSlugAndName(slug, name).ifPresent(secretRepo::delete);
    }

    // --- per-user secrets (git password / PAT) — see UserSecretCipher ---------

    /**
     * Decrypted with a key derived from {@code userId}, not the shared
     * zone-secret key. Returns {@code ""} rather than throwing if that fails
     * (wrong/rotated id, or a row written before this per-user scheme
     * existed) — a page rendering a whole member list shouldn't 500 because
     * one row can't be read back; the caller just treats it as unset.
     */
    public String userSecret(String slug, String name, UUID userId) {
        return secretRepo.findByZoneSlugAndName(slug, name)
                .map(s -> {
                    try {
                        return userCipher.decrypt(s.value(), userId);
                    } catch (RuntimeException e) {
                        log.warn("could not decrypt user secret {}/{} for {}: {}", slug, name, userId, e.getMessage());
                        return "";
                    }
                })
                .orElse("");
    }

    @Transactional
    public void putUserSecret(String slug, String name, String plaintext, UUID userId) {
        String ct = userCipher.encrypt(plaintext, userId);
        secretRepo.findByZoneSlugAndName(slug, name).ifPresentOrElse(
                s -> {
                    s.setValue(ct);
                    secretRepo.save(s);
                },
                () -> secretRepo.save(new ZoneSecret(slug, name, ct)));
    }
}

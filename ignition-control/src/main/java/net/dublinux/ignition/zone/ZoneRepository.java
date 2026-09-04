package net.dublinux.ignition.zone;

import java.util.List;
import java.util.Optional;

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

    private final ZoneEntityRepository zoneRepo;
    private final ZoneSecretRepository secretRepo;
    private final SecretCipher cipher;

    public ZoneRepository(ZoneEntityRepository zoneRepo, ZoneSecretRepository secretRepo, SecretCipher cipher) {
        this.zoneRepo = zoneRepo;
        this.secretRepo = secretRepo;
        this.cipher = cipher;
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
}

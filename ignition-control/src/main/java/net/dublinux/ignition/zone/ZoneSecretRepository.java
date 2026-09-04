package net.dublinux.ignition.zone;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneSecretRepository extends JpaRepository<ZoneSecret, ZoneSecret.Key> {

    List<ZoneSecret> findByZoneSlug(String zoneSlug);

    Optional<ZoneSecret> findByZoneSlugAndName(String zoneSlug, String name);
}

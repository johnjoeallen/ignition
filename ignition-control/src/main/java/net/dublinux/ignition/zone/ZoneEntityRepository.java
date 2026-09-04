package net.dublinux.ignition.zone;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the {@code zone} table. Prefer the {@link ZoneRepository} facade in services. */
public interface ZoneEntityRepository extends JpaRepository<Zone, String> {
}

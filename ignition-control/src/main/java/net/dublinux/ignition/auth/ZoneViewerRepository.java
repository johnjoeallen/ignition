package net.dublinux.ignition.auth;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneViewerRepository extends JpaRepository<ZoneViewer, ZoneViewer.Key> {

    List<ZoneViewer> findByZoneSlug(String zoneSlug);

    boolean existsByZoneSlugAndEmailIgnoreCase(String zoneSlug, String email);
}

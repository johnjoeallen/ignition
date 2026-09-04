package net.dublinux.ignition.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneMemberRepository extends JpaRepository<ZoneMember, ZoneMember.Key> {

    List<ZoneMember> findByZoneSlug(String zoneSlug);

    List<ZoneMember> findByUserId(UUID userId);

    Optional<ZoneMember> findByZoneSlugAndUserId(String zoneSlug, UUID userId);

    long countByZoneSlugAndRole(String zoneSlug, ZoneMember.Role role);
}

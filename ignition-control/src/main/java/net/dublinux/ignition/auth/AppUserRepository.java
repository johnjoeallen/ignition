package net.dublinux.ignition.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<AppUser> findByStatusOrderByCreatedAt(AppUser.Status status);

    long countByPlatformAdminTrueAndStatus(AppUser.Status status);
}

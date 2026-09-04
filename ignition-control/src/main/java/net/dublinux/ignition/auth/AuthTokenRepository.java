package net.dublinux.ignition.auth;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, String> {

    @Transactional
    void deleteByUserIdAndPurpose(UUID userId, AuthToken.Purpose purpose);
}

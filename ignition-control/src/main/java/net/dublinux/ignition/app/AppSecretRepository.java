package net.dublinux.ignition.app;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** App secrets live in the {@code app_secret} table. */
public interface AppSecretRepository extends JpaRepository<AppSecret, AppSecret.Key> {

    List<AppSecret> findByZoneAndAppAndScopeOrderByName(String zone, String app, String scope);

    @org.springframework.transaction.annotation.Transactional
    void deleteByZoneAndApp(String zone, String app);
}

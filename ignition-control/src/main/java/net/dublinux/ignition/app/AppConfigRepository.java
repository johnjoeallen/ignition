package net.dublinux.ignition.app;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** App settings live in the {@code app_config} table. */
public interface AppConfigRepository extends JpaRepository<AppConfig, DeployedApp.Key> {

    List<AppConfig> findByZone(String zone);

    @org.springframework.transaction.annotation.Transactional
    void deleteByZoneAndName(String zone, String name);
}

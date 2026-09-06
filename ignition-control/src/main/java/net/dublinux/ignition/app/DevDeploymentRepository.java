package net.dublinux.ignition.app;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Dev deployments live in the {@code app_dev} table. */
public interface DevDeploymentRepository extends JpaRepository<DevDeployment, DeployedApp.Key> {

    List<DevDeployment> findByZoneOrderByName(String zone);

    Optional<DevDeployment> findByZoneAndName(String zone, String name);

    void deleteByZoneAndName(String zone, String name);
}

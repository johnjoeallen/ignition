package net.dublinux.ignition.app;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

/** Apps live in the {@code app} table. */
public interface AppRepository extends JpaRepository<DeployedApp, DeployedApp.Key> {

    List<DeployedApp> findByZoneOrderByName(String zone);

    Optional<DeployedApp> findByZoneAndName(String zone, String name);

    void deleteByZoneAndName(String zone, String name);

    default List<DeployedApp> findByZone(String zone) {
        return findByZoneOrderByName(zone);
    }

    default List<DeployedApp> findAllOrdered() {
        return findAll(Sort.by("zone", "name"));
    }
}

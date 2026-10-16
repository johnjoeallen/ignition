package net.dublinux.ignition.sweep;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppTrafficRepository extends JpaRepository<AppTraffic, String> {
}

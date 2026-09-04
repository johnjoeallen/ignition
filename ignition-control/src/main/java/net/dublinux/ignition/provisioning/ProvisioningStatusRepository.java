package net.dublinux.ignition.provisioning;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProvisioningStatusRepository extends JpaRepository<ProvisioningStatusEntity, String> {
}

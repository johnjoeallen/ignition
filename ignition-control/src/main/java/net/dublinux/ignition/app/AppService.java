package net.dublinux.ignition.app;

import java.util.List;

import org.springframework.stereotype.Service;

/**
 * Deployed-app views and the CI bridge. Today: list only — {@code deploy} /
 * {@code undeploy} land with the compose runner (DESIGN.md step 4).
 */
@Service
public class AppService {

    private final AppRepository apps;

    public AppService(AppRepository apps) {
        this.apps = apps;
    }

    public List<DeployedApp> list() {
        return apps.findAll();
    }

    public List<DeployedApp> listForZone(String slug) {
        return apps.findByZone(slug);
    }
}

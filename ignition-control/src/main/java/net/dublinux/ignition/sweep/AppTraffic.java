package net.dublinux.ignition.sweep;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Last Traefik-seen request for a compose project. Row in {@code app_traffic}. */
@Entity
@Table(name = "app_traffic")
public class AppTraffic {

    @Id
    private String project;

    @Column(name = "last_traffic_at", nullable = false)
    private Instant lastTrafficAt;

    protected AppTraffic() {
    }

    public AppTraffic(String project, Instant lastTrafficAt) {
        this.project = project;
        this.lastTrafficAt = lastTrafficAt;
    }

    public String project() { return project; }
    public Instant lastTrafficAt() { return lastTrafficAt; }
    public void seen(Instant at) { this.lastTrafficAt = at; }
}

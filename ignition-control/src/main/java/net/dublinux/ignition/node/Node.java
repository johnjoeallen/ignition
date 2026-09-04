package net.dublinux.ignition.node;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A host that runs zone stacks. Row in {@code node}. */
@Entity
@Table(name = "node")
public class Node {

    public enum State { ACTIVE, DRAINING }

    @Id
    private String name;

    @Column(name = "docker_host", nullable = false)
    private String dockerHost;

    private double cpus;

    @Column(name = "mem_gb", nullable = false)
    private double memGb;

    /** Comma-joined in the column; exposed as a list. */
    @Column(nullable = false)
    private String labels = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state = State.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Node() {
    }

    public Node(String name, String dockerHost, double cpus, double memGb,
                List<String> labels, State state) {
        this.name = name;
        this.dockerHost = dockerHost;
        this.cpus = cpus;
        this.memGb = memGb;
        this.labels = labels == null ? "" : String.join(",", labels);
        this.state = state == null ? State.ACTIVE : state;
    }

    public String name() {
        return name;
    }

    public String dockerHost() {
        return dockerHost;
    }

    public double cpus() {
        return cpus;
    }

    public double memGb() {
        return memGb;
    }

    public List<String> labels() {
        return labels == null || labels.isBlank() ? List.of() : List.of(labels.split("\\s*,\\s*"));
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public void setDockerHost(String dockerHost) {
        this.dockerHost = dockerHost;
    }

    public void setCpus(double cpus) {
        this.cpus = cpus;
    }

    public void setMemGb(double memGb) {
        this.memGb = memGb;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels == null ? "" : String.join(",", labels);
    }

    public boolean hasLabel(String label) {
        return label == null || label.isBlank() || labels().contains(label);
    }
}

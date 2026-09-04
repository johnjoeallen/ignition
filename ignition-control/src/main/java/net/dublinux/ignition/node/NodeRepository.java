package net.dublinux.ignition.node;

import org.springframework.data.jpa.repository.JpaRepository;

/** Nodes live in the {@code node} table. */
public interface NodeRepository extends JpaRepository<Node, String> {
}

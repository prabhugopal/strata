package io.strata.management;

import io.strata.spi.StateStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** What a runtime exposes to management tools. */
public interface Introspection {
    /** This node's address. */
    String node();

    Instant startedAt();

    Observability observability();

    StateStore store();

    CompletionStage<ClusterView> cluster();

    /** Whether this node can take traffic: it has joined its cluster and is up. */
    boolean ready();

    /**
     * Whether this node is still of use: false once it has been removed from its cluster (downed,
     * e.g. by the split-brain resolver) and can never rejoin as it is. An orchestrator should
     * restart it; the new process joins again.
     */
    default boolean live() { return true; }

    /** The cluster as this node sees it, and how many entities of each type each node holds. */
    record ClusterView(String self, String leader, List<Member> members, Map<String, Map<String, Integer>> entitiesByTypeAndNode) {}

    record Member(String address, String status, Set<String> roles, boolean reachable) {}
}

package io.strata.gradle;

import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/** {@code strata { kubernetes { } }}: how the app runs as a Strata cluster on Kubernetes. */
public abstract class KubernetesSpec {
    /** Name of the StatefulSet and Services. Default: the project name. */
    public abstract Property<String> getName();
    public abstract Property<String> getNamespace();
    /** Cluster size; each pod is a Strata node. */
    public abstract Property<Integer> getReplicas();
    public abstract Property<Integer> getHttpPort();
    public abstract Property<Integer> getRemotingPort();
    /** ClusterIP, NodePort or LoadBalancer for the HTTP service. */
    public abstract Property<String> getServiceType();
    public abstract Property<String> getCpu();
    public abstract Property<String> getMemory();
    public abstract Property<Integer> getMaxRamPercentage();
    public abstract Property<Integer> getTerminationGraceSeconds();
    public abstract Property<String> getRolloutTimeout();
    /** minikube, kind, push or none: how the image gets into the cluster. */
    public abstract Property<String> getLoad();
    public abstract Property<String> getKubectl();
    public abstract Property<String> getMinikube();
    public abstract Property<String> getKind();
    /** Deploy a development PostgreSQL next to the app (off: set {@link #getJdbcUrl()}). */
    public abstract Property<Boolean> getPostgres();
    public abstract Property<String> getPostgresImage();
    public abstract Property<String> getPostgresStorage();
    public abstract Property<String> getPostgresDatabase();
    public abstract Property<String> getPostgresUser();
    /** JDBC URL of an existing database (when postgres is off). Credentials come from Secret {@code <name>-db}. */
    public abstract Property<String> getJdbcUrl();
    /**
     * Encrypt traffic between nodes: the name of a Secret holding tls.key, tls.crt and ca.crt (what
     * cert-manager creates from a Certificate). Empty: plain TCP between nodes.
     */
    public abstract Property<String> getTlsSecret();

    /** Split-brain strategy (strata.pekko.split-brain.strategy); lease-majority uses the shared database. */
    public abstract Property<String> getSplitBrain();

    /** Extra environment variables for every node (any strata.* setting via its env var). */
    public abstract MapProperty<String, String> getEnv();
}

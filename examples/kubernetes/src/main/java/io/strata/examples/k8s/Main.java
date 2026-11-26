package io.strata.examples.k8s;

import io.strata.Strata;
import io.strata.StrataRuntime;

/** One Strata node. On Kubernetes the plugin's manifests configure it through environment variables. */
public final class Main {
    public static void main(String[] args) {
        StrataRuntime strata = Strata.start();
        Runtime.getRuntime().addShutdownHook(new Thread(strata::close, "strata-shutdown"));
    }
}

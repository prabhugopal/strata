package io.strata.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.testing.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * {@code id("io.strata")}: adds Strata to a JVM project. With the java plugin it adds the
 * Strata BOM, strata-core, the runtime modules chosen in {@code strata { }}, strata-processor
 * (javac, and kapt when the Kotlin kapt plugin is applied), and the JVM flags Strata needs to
 * tests, JavaExec tasks and application start scripts. Task {@code strataInfo} shows the result.
 */
public final class StrataPlugin implements Plugin<Project> {
    private static final String PROPERTIES = "strata-plugin.properties";
    private static final String DEPLOY_DEFAULTS = "deploy-defaults.properties";

    @Override
    public void apply(Project project) {
        Properties props = load(PROPERTIES);
        String group = props.getProperty("group");
        StrataExtension ext = project.getExtensions().create("strata", StrataExtension.class);
        ext.getVersion().convention(props.getProperty("version"));
        ext.getSpring().convention(false);
        ext.getHttp().convention(ext.getSpring().map(spring -> !spring));
        ext.getJdbc().convention(true);
        ext.getClojure().convention(false);
        ext.getProcessor().convention(true);
        ext.getJvmArgs().convention(split(props.getProperty("jvmArgs")));

        project.getPluginManager().withPlugin("java", java -> {
            DependencyHandler deps = project.getDependencies();
            Provider<Dependency> bom = ext.getVersion().map(v -> deps.platform(group + ":strata-bom:" + v));
            Provider<Dependency> processor = ext.getVersion().map(v -> deps.create(group + ":strata-processor:" + v));

            project.getConfigurations().named("implementation", c -> {
                c.getDependencies().addLater(bom);
                c.getDependencies().addAllLater(ext.getVersion().map(v -> {
                    List<Dependency> out = new ArrayList<>();
                    out.add(deps.create(group + ":strata-core:" + v));
                    if (ext.getSpring().get()) out.add(deps.create(group + ":strata-spring-boot-starter:" + v));
                    if (ext.getClojure().get()) out.add(deps.create(group + ":strata-clojure:" + v));
                    return out;
                }));
            });
            project.getConfigurations().named("runtimeOnly", c -> c.getDependencies().addAllLater(ext.getVersion().map(v -> {
                List<Dependency> out = new ArrayList<>();
                out.add(deps.create(group + ":strata-pekko:" + v));
                if (ext.getHttp().get()) out.add(deps.create(group + ":strata-http:" + v));
                if (ext.getJdbc().get()) out.add(deps.create(group + ":strata-store-jdbc:" + v));
                return out;
            })));
            project.getConfigurations().named("annotationProcessor", c -> {
                c.getDependencies().addLater(bom);
                c.getDependencies().addAllLater(ext.getProcessor().zip(processor, (on, p) -> on ? List.of(p) : List.of()));
            });
            project.getPluginManager().withPlugin("org.jetbrains.kotlin.kapt", kapt ->
                    project.getConfigurations().named("kapt", c ->
                            c.getDependencies().addAllLater(ext.getProcessor().zip(processor, (on, p) -> on ? List.of(p) : List.of()))));

            project.getTasks().withType(Test.class).configureEach(t -> t.getJvmArgumentProviders().add(() -> ext.getJvmArgs().get()));
            project.getTasks().withType(JavaExec.class).configureEach(t -> t.getJvmArgumentProviders().add(() -> ext.getJvmArgs().get()));
        });

        // An app that is only entities needs no main class of its own.
        project.getPluginManager().withPlugin("application", app ->
                project.getExtensions().getByType(JavaApplication.class).getMainClass().convention(props.getProperty("mainClass")));
        project.getPluginManager().withPlugin("application", app -> project.afterEvaluate(p -> {
            JavaApplication a = p.getExtensions().getByType(JavaApplication.class);
            List<String> args = new ArrayList<>();
            a.getApplicationDefaultJvmArgs().forEach(args::add);
            for (String x : ext.getJvmArgs().get()) if (!args.contains(x)) args.add(x);
            a.setApplicationDefaultJvmArgs(args);
        }));

        deployment(project, ext, props);

        project.getTasks().register("strataInfo", t -> {
            t.setGroup("help");
            t.setDescription("Shows the Strata version, modules and JVM flags this project uses.");
            Provider<String> summary = ext.getVersion().map(v -> "Strata " + v
                    + "\n  modules: strata-core, strata-pekko"
                    + (ext.getHttp().get() ? ", strata-http" : "")
                    + (ext.getSpring().get() ? ", strata-spring-boot-starter" : "")
                    + (ext.getJdbc().get() ? ", strata-store-jdbc" : "")
                    + (ext.getClojure().get() ? ", strata-clojure" : "")
                    + "\n  processor: " + (ext.getProcessor().get() ? "on" : "off")
                    + "\n  JVM flags: " + String.join(" ", ext.getJvmArgs().get())
                    + "\n  needs Java " + props.getProperty("minJava") + " or newer");
            t.doLast(x -> x.getLogger().quiet(summary.get()));
        });
    }

    /** Image and Kubernetes conventions (from deploy-defaults.properties) and tasks. */
    private static void deployment(Project project, StrataExtension ext, Properties props) {
        Properties d = load(DEPLOY_DEFAULTS);
        ImageSpec img = ext.getImage();
        KubernetesSpec k = ext.getKubernetes();
        img.getName().convention(project.getName());
        img.getTag().convention(d.getProperty("image.tag"));
        img.getBase().convention(project.provider(() -> d.getProperty("image.base").replace("{java}", targetJava(project, props))));
        img.getBuilder().convention(d.getProperty("image.builder"));
        k.getName().convention(project.getName());
        k.getNamespace().convention(d.getProperty("kubernetes.namespace"));
        k.getReplicas().convention(Integer.parseInt(d.getProperty("kubernetes.replicas")));
        k.getHttpPort().convention(Integer.parseInt(d.getProperty("kubernetes.httpPort")));
        k.getRemotingPort().convention(Integer.parseInt(d.getProperty("kubernetes.remotingPort")));
        k.getServiceType().convention(d.getProperty("kubernetes.serviceType"));
        k.getCpu().convention(d.getProperty("kubernetes.cpu"));
        k.getMemory().convention(d.getProperty("kubernetes.memory"));
        k.getMaxRamPercentage().convention(Integer.parseInt(d.getProperty("kubernetes.maxRamPercentage")));
        k.getTerminationGraceSeconds().convention(Integer.parseInt(d.getProperty("kubernetes.terminationGraceSeconds")));
        k.getRolloutTimeout().convention(d.getProperty("kubernetes.rolloutTimeout"));
        k.getLoad().convention(d.getProperty("kubernetes.load"));
        k.getKubectl().convention(d.getProperty("kubernetes.kubectl"));
        k.getMinikube().convention(d.getProperty("kubernetes.minikube"));
        k.getKind().convention(d.getProperty("kubernetes.kind"));
        k.getPostgres().convention(Boolean.parseBoolean(d.getProperty("kubernetes.postgres")));
        k.getPostgresImage().convention(d.getProperty("kubernetes.postgresImage"));
        k.getPostgresStorage().convention(d.getProperty("kubernetes.postgresStorage"));
        k.getPostgresDatabase().convention(d.getProperty("kubernetes.postgresDatabase"));
        k.getPostgresUser().convention(d.getProperty("kubernetes.postgresUser"));
        k.getJdbcUrl().convention("");
        k.getTlsSecret().convention(d.getProperty("kubernetes.tlsSecret"));
        k.getSplitBrain().convention(d.getProperty("kubernetes.splitBrain"));
        Provider<String> image = img.getName().zip(img.getTag(), (n, t) -> n + ":" + t);
        var build = project.getLayout().getBuildDirectory();

        project.getPluginManager().withPlugin("application", app -> {
            var tasks = project.getTasks();
            var dockerfile = tasks.register("strataDockerfile", DeployTasks.Dockerfile.class, t -> {
                t.setGroup("strata");
                t.setDescription("Writes a Dockerfile and the app's distribution to build/strata/image.");
                var install = tasks.named("installDist", org.gradle.api.tasks.Sync.class);
                t.dependsOn(install);
                t.getDistribution().set(project.getLayout().dir(install.map(org.gradle.api.tasks.Sync::getDestinationDir)));
                t.getBase().set(img.getBase());
                t.getAppName().set(project.provider(() -> project.getExtensions().getByType(JavaApplication.class).getApplicationName()));
                t.getPorts().set(k.getHttpPort().zip(k.getRemotingPort(), List::of));
                t.getContext().set(build.dir("strata/image"));
            });
            var imageTask = tasks.register("strataImage", DeployTasks.Commands.class, t -> {
                t.setGroup("strata");
                t.setDescription("Builds the container image (strata.image).");
                t.dependsOn(dockerfile);
                t.getCommands().set(image.zip(dockerfile.flatMap(DeployTasks.Dockerfile::getContext), (i, ctx) ->
                        List.of(List.of(img.getBuilder().get(), "build", "-t", i, ctx.getAsFile().getAbsolutePath()))));
            });
            var manifests = tasks.register("strataManifests", DeployTasks.ManifestsTask.class, t -> {
                t.setGroup("strata");
                t.setDescription("Writes Kubernetes manifests to build/strata/k8s (strata.kubernetes).");
                t.getAppName().set(k.getName());
                t.getNamespace().set(k.getNamespace());
                t.getImage().set(image);
                t.getReplicas().set(k.getReplicas());
                t.getHttpPort().set(k.getHttpPort());
                t.getRemotingPort().set(k.getRemotingPort());
                t.getServiceType().set(k.getServiceType());
                t.getCpu().set(k.getCpu());
                t.getMemory().set(k.getMemory());
                t.getMaxRamPercentage().set(k.getMaxRamPercentage());
                t.getGraceSeconds().set(k.getTerminationGraceSeconds());
                t.getManagementBase().set(props.getProperty("managementBase"));
                t.getPostgres().set(k.getPostgres());
                t.getPostgresImage().set(k.getPostgresImage());
                t.getPostgresStorage().set(k.getPostgresStorage());
                t.getDatabase().set(k.getPostgresDatabase());
                t.getUser().set(k.getPostgresUser());
                t.getJdbcUrl().set(k.getJdbcUrl());
                t.getEnv().set(k.getEnv());
                t.getTlsSecret().set(k.getTlsSecret());
                t.getSplitBrain().set(k.getSplitBrain());
                t.getTlsMountPath().set(d.getProperty("kubernetes.tlsMountPath"));
                t.getPasswordFile().set(project.getLayout().getProjectDirectory().file(".strata/postgres-password"));
                t.getOutput().set(build.dir("strata/k8s"));
            });
            tasks.register("strataDeploy", DeployTasks.Commands.class, t -> {
                t.setGroup("strata");
                t.setDescription("Builds the image, loads it into the cluster, applies the manifests and waits for the rollout.");
                t.dependsOn(imageTask, manifests);
                t.getCommands().set(image.zip(manifests.flatMap(DeployTasks.ManifestsTask::getOutput), (i, dir) -> {
                    List<List<String>> cmds = new ArrayList<>(DeployTasks.loadCommands(k.getLoad().get(), i, k, img));
                    String kc = k.getKubectl().get(), ns = k.getNamespace().get(), sts = "statefulset/" + k.getName().get();
                    cmds.add(List.of(kc, "apply", "-f", dir.getAsFile().getAbsolutePath()));
                    // The same tag may now hold a new image: roll the nodes one at a time onto it.
                    cmds.add(List.of(kc, "rollout", "restart", sts, "-n", ns));
                    cmds.add(List.of(kc, "rollout", "status", sts, "-n", ns, "--timeout=" + k.getRolloutTimeout().get()));
                    return cmds;
                }));
            });
            tasks.register("strataUndeploy", DeployTasks.Commands.class, t -> {
                t.setGroup("strata");
                t.setDescription("Deletes what strataDeploy created (the development database's volume too).");
                t.dependsOn(manifests);
                t.getCommands().set(manifests.flatMap(DeployTasks.ManifestsTask::getOutput).map(dir ->
                        List.of(List.of(k.getKubectl().get(), "delete", "--ignore-not-found", "-f", dir.getAsFile().getAbsolutePath()))));
            });
        });
    }

    /** The Java version the app's classes need: compileJava's release, else its toolchain, else Strata's minimum. */
    private static String targetJava(Project project, Properties props) {
        var compile = project.getTasks().withType(org.gradle.api.tasks.compile.JavaCompile.class).findByName("compileJava");
        if (compile != null && compile.getOptions().getRelease().isPresent()) return String.valueOf(compile.getOptions().getRelease().get());
        var java = project.getExtensions().findByType(org.gradle.api.plugins.JavaPluginExtension.class);
        if (java != null && java.getToolchain().getLanguageVersion().isPresent()) return java.getToolchain().getLanguageVersion().get().toString();
        return props.getProperty("minJava");
    }

    private static List<String> split(String s) {
        return s == null || s.isBlank() ? List.of() : Arrays.stream(s.trim().split("\\s+")).toList();
    }

    private static Properties load(String name) {
        Properties p = new Properties();
        try (InputStream in = StrataPlugin.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException(name + " is missing from the Strata plugin");
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't read " + name, e);
        }
        return p;
    }
}

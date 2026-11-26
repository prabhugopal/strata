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

    @Override
    public void apply(Project project) {
        Properties props = load();
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

        project.getPluginManager().withPlugin("application", app -> project.afterEvaluate(p -> {
            JavaApplication a = p.getExtensions().getByType(JavaApplication.class);
            List<String> args = new ArrayList<>();
            a.getApplicationDefaultJvmArgs().forEach(args::add);
            for (String x : ext.getJvmArgs().get()) if (!args.contains(x)) args.add(x);
            a.setApplicationDefaultJvmArgs(args);
        }));

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

    private static List<String> split(String s) {
        return s == null || s.isBlank() ? List.of() : Arrays.stream(s.trim().split("\\s+")).toList();
    }

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = StrataPlugin.class.getResourceAsStream(PROPERTIES)) {
            if (in == null) throw new IllegalStateException(PROPERTIES + " is missing from the Strata plugin");
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't read " + PROPERTIES, e);
        }
        return p;
    }
}

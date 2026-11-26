package io.strata.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds and runs a fresh app that only applies {@code id("io.strata")}: the processor must
 * generate the entity, the runtime must be on the classpath, and the JVM flags must reach `run`.
 */
class StrataPluginTest {
    @TempDir Path dir;

    void write(String path, String text) throws IOException {
        Path p = dir.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    void project(String strataBlock) throws IOException {
        String repo = System.getProperty("strata.test.repo").replace("\\", "/");
        String jdks = System.getProperty("strata.test.jdks");
        write("settings.gradle.kts", "rootProject.name = \"demo\"\n");
        write("gradle.properties", jdks == null ? "" : "org.gradle.java.installations.paths=" + jdks + "\n");
        write("build.gradle.kts", """
                plugins {
                    java
                    application
                    id("io.strata")
                }
                repositories {
                    maven { url = uri("%s") }
                    mavenCentral()
                }
                java { toolchain.languageVersion.set(JavaLanguageVersion.of(%s)) }
                application { mainClass.set("demo.Main") }
                %s
                """.formatted(repo, System.getProperty("strata.test.toolchain"), strataBlock));
        write("src/main/java/demo/Counter.java", """
                package demo;
                import io.strata.annotation.*;
                @Entity
                public class Counter {
                    private long count;
                    @Command public long increment() { return ++count; }
                }
                """);
        write("src/main/java/demo/Main.java", """
                package demo;
                import com.typesafe.config.ConfigFactory;
                import io.strata.Strata;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        var cfg = ConfigFactory.parseString(
                                "strata.http.port = 0\\nstrata.pekko.system.pekko.remote.artery.canonical.port = 0")
                            .withFallback(ConfigFactory.load()).resolve();
                        try (var strata = Strata.start(cfg)) {
                            var c = CounterClient.of(strata, "a");
                            c.increment().toCompletableFuture().get();
                            System.out.println("count=" + c.increment().toCompletableFuture().get());
                            System.out.println("jvm-flags=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
                        }
                    }
                }
                """);
    }

    BuildResult gradle(String... args) {
        return GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath().withArguments(args).forwardOutput().build();
    }

    @Test
    void anAppWithOnlyThePluginBuildsAndRuns() throws IOException {
        project("");
        String out = gradle("run", "-q", "--stacktrace").getOutput();
        assertTrue(out.contains("count=2"), out);
        assertTrue(out.contains("--add-opens=java.base/java.nio=ALL-UNNAMED"), "JVM flags reach `run`:\n" + out);

        String scripts = gradle("startScripts", "-q").getOutput();
        assertTrue(Files.readString(dir.resolve("build/scripts/demo")).contains("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"), scripts);
    }

    @Test
    void modulesFollowTheStrataBlock() throws IOException {
        project("strata { spring.set(true); jdbc.set(false) }");
        String info = gradle("strataInfo", "-q").getOutput();
        assertTrue(info.contains("strata-spring-boot-starter"), info);
        assertTrue(!info.contains("strata-http") && !info.contains("strata-store-jdbc"), info);
    }
}

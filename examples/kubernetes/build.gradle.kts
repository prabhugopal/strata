plugins {
    java
    application
    id("io.strata")
}

repositories { mavenCentral() }

dependencies { runtimeOnly("ch.qos.logback:logback-classic:1.6.8") }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("strata.java.toolchain").getOrElse("21").toInt())) }
// Compile for Java 21; the plugin picks a matching JRE image (eclipse-temurin:21-jre).
tasks.withType<JavaCompile>().configureEach { options.release.set(21); options.compilerArgs.add("-parameters") }

application { mainClass.set("io.strata.examples.k8s.Main") }

strata {
    kubernetes {
        replicas.set(3)
        // Small enough for a laptop minikube; raise for real workloads.
        memory.set("384Mi")
        cpu.set("250m")
    }
}

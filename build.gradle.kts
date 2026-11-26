// Shared conventions. Versions come from gradle/libs.versions.toml; the Java baseline
// and coordinates from gradle.properties. Nothing here should name a version.
val javaRelease = providers.gradleProperty("strata.java.release").get().toInt()
val javaToolchain = providers.gradleProperty("strata.java.toolchain").get().toInt()
val strataJvmArgs = providers.gradleProperty("strata.jvm.args").get().split(" ").filter { it.isNotBlank() }

/** Modules users depend on: published, and listed in strata-bom. */
val published = listOf("strata-core", "strata-processor", "strata-pekko", "strata-http", "strata-store-jdbc",
    "strata-spring-boot-starter", "strata-clojure")
extra["published"] = published
// A repository inside the build, for the Gradle plugin's tests (and for trying local snapshots).
val localRepo = layout.buildDirectory.dir("repo")
extra["localRepo"] = localRepo

allprojects {
    group = providers.gradleProperty("strata.group").get()
    version = providers.gradleProperty("strata.version").get()
    repositories { mavenCentral() }
}

subprojects {
    if (name in published || name == "strata-bom") {
        apply(plugin = "maven-publish")
        extensions.configure<PublishingExtension> {
            repositories { maven { name = "local"; url = uri(localRepo) } }
            pluginManager.withPlugin("java") {
                publications { create<MavenPublication>("maven") { from(components["java"]) } }
            }
        }
    }
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(javaToolchain))
            withSourcesJar()
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(javaRelease)
            options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-parameters"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            jvmArgs(strataJvmArgs)
            testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
        tasks.withType<JavaExec>().configureEach { jvmArgs(strataJvmArgs) }
    }
}

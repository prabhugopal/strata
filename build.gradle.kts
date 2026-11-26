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

// Where a release is staged before it's uploaded to Maven Central (see .github/workflows/release.yml).
val stagingRepo = layout.buildDirectory.dir("staging")
extra["stagingRepo"] = stagingRepo
fun prop(name: String) = providers.gradleProperty(name).get()

subprojects {
    if (name in published || name == "strata-bom" || name == "strata-gradle-plugin") {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")
        extensions.configure<PublishingExtension> {
            repositories {
                maven { name = "local"; url = uri(localRepo) }
                maven { name = "staging"; url = uri(stagingRepo) }
            }
            // Libraries publish their jar here; strata-bom and the Gradle plugin declare their own.
            if (name in published) {
                pluginManager.withPlugin("java") {
                    publications { create<MavenPublication>("maven") { from(components["java"]) } }
                }
            }
            // What Maven Central requires of every POM; values from gradle.properties.
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set(provider { project.description ?: "Strata: ${project.name}" })
                    url.set(prop("strata.pom.url"))
                    licenses { license { name.set(prop("strata.pom.license")); url.set(prop("strata.pom.license.url")) } }
                    developers { developer { id.set(prop("strata.pom.developer")); name.set(prop("strata.pom.developer.name")); url.set(prop("strata.pom.developer.url")) } }
                    scm { url.set(prop("strata.pom.url")); connection.set(prop("strata.pom.scm")); developerConnection.set(prop("strata.pom.scm")) }
                }
            }
        }
        // Signed only when a key is given (releases, in CI): STRATA_SIGNING_KEY (ASCII-armoured) and
        // STRATA_SIGNING_PASSWORD. Local builds and local publishing need neither.
        val signingKey = providers.environmentVariable("STRATA_SIGNING_KEY")
        extensions.configure<SigningExtension> {
            isRequired = false
            if (signingKey.isPresent) {
                useInMemoryPgpKeys(signingKey.get(), providers.environmentVariable("STRATA_SIGNING_PASSWORD").orNull)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(javaToolchain))
            withSourcesJar()
            withJavadocJar()   // Maven Central requires one per module
        }
        // Javadoc is published, not a gate: a doc-comment slip shouldn't fail a build.
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
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
        // The version a node reports (management API /info, Stratum, strata info).
        tasks.withType<Jar>().configureEach {
            manifest { attributes("Implementation-Title" to project.name, "Implementation-Version" to project.version) }
        }
    }
}

// Shared conventions. Versions come from gradle/libs.versions.toml; the Java baseline
// and coordinates from gradle.properties. Nothing here should name a version.
val javaRelease = providers.gradleProperty("strata.java.release").get().toInt()
val javaToolchain = providers.gradleProperty("strata.java.toolchain").get().toInt()
val strataJvmArgs = providers.gradleProperty("strata.jvm.args").get().split(" ").filter { it.isNotBlank() }

allprojects {
    group = providers.gradleProperty("strata.group").get()
    version = providers.gradleProperty("strata.version").get()
    repositories { mavenCentral() }
}

subprojects {
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

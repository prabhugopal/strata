plugins {
    `java-gradle-plugin`
    alias(libs.plugins.gradle.plugin.publish)   // publishPlugins: the Gradle Plugin Portal
}

description = "Gradle plugin: Strata dependencies (BOM-aligned), the annotation processor, and the JVM flags Strata needs."

// Runs inside users' Gradle builds, so it targets what Gradle runs on (gradle.properties).
val pluginRelease = providers.gradleProperty("strata.gradle-plugin.java.release").get().toInt()
tasks.withType<JavaCompile>().configureEach { options.release.set(pluginRelease) }

gradlePlugin {
    website.set(providers.gradleProperty("strata.pom.url"))
    vcsUrl.set(providers.gradleProperty("strata.pom.url"))
    plugins {
        create("strata") {
            // The Plugin Portal, like Maven Central, accepts only an id under a namespace you own.
            id = providers.gradleProperty("strata.plugin.id").get()
            implementationClass = "io.strata.gradle.StrataPlugin"
            displayName = "Strata"
            description = project.description
            tags.set(listOf("durable-execution", "pekko", "actors", "kubernetes"))
        }
    }
}

// What the plugin adds, decided by this build: Strata's version and required JVM flags.
val generated = layout.buildDirectory.dir("generated/strata-plugin")
val pluginProperties = tasks.register("strataPluginProperties") {
    val version = project.version.toString()
    val group = project.group.toString()
    val jvmArgs = providers.gradleProperty("strata.jvm.args").get()
    val minJava = providers.gradleProperty("strata.java.release").get()
    // The management API's base path, read from strata-core's reference.conf (its single source).
    val conf = rootProject.file("strata-core/src/main/resources/reference.conf").readText()
    val managementBase = Regex("""management \{.*?base-path = "([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(conf)?.groupValues?.get(1)
        ?: error("strata.management.base-path not found in strata-core's reference.conf")
    inputs.property("values", listOf(version, group, jvmArgs, minJava, managementBase))
    outputs.dir(generated)
    doLast {
        val f = generated.get().file("io/strata/gradle/strata-plugin.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$version\ngroup=$group\njvmArgs=$jvmArgs\nminJava=$minJava\nmanagementBase=$managementBase\nmainClass=io.strata.StrataMain\n")
    }
}
sourceSets.main { resources.srcDir(pluginProperties) }

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

@Suppress("UNCHECKED_CAST")
val published = rootProject.extra["published"] as List<String>
val localRepo = rootProject.extra["localRepo"] as Provider<Directory>

tasks.test {
    // The functional test builds a sample app against freshly published Strata modules.
    (published + "strata-bom").forEach { dependsOn(":$it:publishAllPublicationsToLocalRepository") }
    systemProperty("strata.test.repo", localRepo.get().asFile.absolutePath)
    systemProperty("strata.test.pluginId", providers.gradleProperty("strata.plugin.id").get())
    systemProperty("strata.test.toolchain", providers.gradleProperty("strata.java.toolchain").get())
    providers.gradleProperty("org.gradle.java.installations.paths").orNull?.let { systemProperty("strata.test.jdks", it) }
}

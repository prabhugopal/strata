plugins { alias(libs.plugins.kotlin.jvm) }

description = "Strata from Kotlin: the same functional API, Kotlin lambdas as handlers."

kotlin {
    jvmToolchain(providers.gradleProperty("strata.java.toolchain").get().toInt())
    // Emit bytecode for the same Java release as the Java modules (gradle.properties).
    compilerOptions.jvmTarget.set(
        org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(providers.gradleProperty("strata.java.release").get()))
}

dependencies {
    implementation(project(":strata-core"))
    runtimeOnly(project(":strata-pekko"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback)
}

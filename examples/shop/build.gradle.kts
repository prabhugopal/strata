plugins { application }

description = "Showcase: a shop as durable entities, using every Strata construct. No main, no wiring."

dependencies {
    implementation(project(":strata-core"))
    annotationProcessor(project(":strata-processor"))
    runtimeOnly(project(":strata-pekko"))
    runtimeOnly(project(":strata-http"))
    runtimeOnly(project(":strata-store-jdbc"))
    runtimeOnly(libs.logback)

    testImplementation(project(":strata-http"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// Apps using the io.strata Gradle plugin get these two lines for free.
application {
    mainClass.set("io.strata.StrataMain")
    applicationDefaultJvmArgs = providers.gradleProperty("strata.jvm.args").get().split(" ").filter { it.isNotBlank() }
}

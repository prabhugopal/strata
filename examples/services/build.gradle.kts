plugins { `java-library` }

description = "Example: entities using services (@Inject), provided by a plain app without a DI container."

dependencies {
    implementation(project(":strata-core"))
    annotationProcessor(project(":strata-processor"))
    runtimeOnly(project(":strata-pekko"))
    testRuntimeOnly(project(":strata-store-jdbc"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback)
}

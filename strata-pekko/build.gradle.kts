plugins { `java-library` }

description = "Strata's native runtime on Apache Pekko: cluster sharding, single-writer entities, passivation."

dependencies {
    api(project(":strata-core"))
    implementation(libs.pekko.actor.typed)
    implementation(libs.pekko.cluster.typed)
    implementation(libs.pekko.cluster.sharding.typed)
    implementation(libs.pekko.serialization.jackson)
    implementation(libs.pekko.slf4j)

    testImplementation(project(":strata-store-jdbc"))
    testAnnotationProcessor(project(":strata-processor"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback)
}

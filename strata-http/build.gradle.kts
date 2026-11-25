plugins { `java-library` }

description = "HTTP routes for entity types (@Entity mount), served by Pekko HTTP. No Spring."

dependencies {
    api(project(":strata-pekko"))
    implementation(libs.pekko.http)
    implementation(libs.pekko.stream)
    implementation(libs.pekko.actor.typed)

    testAnnotationProcessor(project(":strata-processor"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback)
}

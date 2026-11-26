plugins { application }

description = "Example: the durable counter on Strata with Pekko HTTP. No Spring."

dependencies {
    implementation(project(":strata-core"))
    annotationProcessor(project(":strata-processor"))
    runtimeOnly(project(":strata-http"))
    runtimeOnly(project(":strata-store-jdbc"))
    runtimeOnly(libs.logback)

    testImplementation(project(":strata-http"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("io.strata.StrataMain") // no Main of our own: the app is just its entities
    applicationDefaultJvmArgs = providers.gradleProperty("strata.jvm.args").get().split(" ").filter { it.isNotBlank() }
}

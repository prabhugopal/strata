plugins { scala }

description = "Strata from Scala 3: annotated classes (built by reflection, listed in config) and the builder API."

dependencies {
    implementation(libs.scala3.library)
    implementation(project(":strata-core"))
    runtimeOnly(libs.jackson.module.scala3) // Scala collections and Option in state, arguments and results
    runtimeOnly(project(":strata-pekko"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback)
}

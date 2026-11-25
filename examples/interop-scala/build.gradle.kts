plugins { scala }

description = "Strata from Scala 3: the same functional API, Scala lambdas as handlers."

dependencies {
    implementation(libs.scala3.library)
    implementation(project(":strata-core"))
    runtimeOnly(project(":strata-pekko"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback)
}

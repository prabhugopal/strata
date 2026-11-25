plugins { `java-library` }

description = "Strata from Clojure: the same functional API through Java interop."

sourceSets.main { resources.srcDir("src/main/clojure") }

dependencies {
    implementation(libs.clojure)
    implementation(project(":strata-core"))
    runtimeOnly(project(":strata-pekko"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback)
}

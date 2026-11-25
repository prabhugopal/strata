plugins { `java-library` }

description = "Strata from Clojure: defentity (strata-clojure) and the builder API through Java interop."

sourceSets.main { resources.srcDir("src/main/clojure") }

dependencies {
    implementation(libs.clojure)
    implementation(project(":strata-clojure"))
    runtimeOnly(project(":strata-pekko"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.logback)
}

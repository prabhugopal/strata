plugins { `java-library` }

description = "Strata core API: entity definitions, runtime and storage SPIs, settings. No Pekko or Spring."

dependencies {
    api(libs.typesafe.config)
    api(libs.jackson.databind)
    implementation(libs.jackson.jdk8)
    implementation(libs.asm) // reads entity bytecode for the seal (strata.seal)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

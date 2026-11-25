plugins { `java-library` }

description = "JDBC state store for Strata (PostgreSQL in production, H2 for local development and tests)."

dependencies {
    api(project(":strata-core"))
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

plugins { `java-library` }

description = "Compile-time code generation for @Entity classes: the EntityType, its discovery provider and a typed client."

dependencies {
    implementation(project(":strata-core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

plugins { `java-library` }

description = "Spring Boot auto-configuration for Strata: application.yaml strata.* settings, EntityType beans, entity routes on WebFlux, lifecycle."

dependencies {
    api(project(":strata-core"))
    runtimeOnly(project(":strata-pekko"))
    runtimeOnly(project(":strata-store-jdbc"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.webflux) // routes are served only when the app uses WebFlux
}

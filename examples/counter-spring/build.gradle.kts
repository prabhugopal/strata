plugins { application }

description = "Example: a durable counter behind Spring WebFlux, on Strata."

dependencies {
    implementation(project(":strata-spring-boot-starter"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.webflux)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("io.strata.examples.counter.CounterApplication")
    // The packaged start script carries the JVM flags Strata needs (gradle.properties: strata.jvm.args).
    applicationDefaultJvmArgs = providers.gradleProperty("strata.jvm.args").get().split(" ").filter { it.isNotBlank() }
}

plugins {
    // Downloads the configured JDK (gradle.properties: strata.java) when it isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "strata"

include(
    "strata-core",
    "strata-store-jdbc",
    "strata-pekko",
    "strata-http",
    "strata-processor",
    "strata-spring-boot-starter",
    "examples:counter-spring",
    "examples:counter-pekko",
    "examples:interop-kotlin",
    "examples:interop-scala",
    "examples:interop-clojure",
)

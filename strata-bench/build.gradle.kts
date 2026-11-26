plugins { application }

description = "Throughput and latency benchmark: many concurrent callers on many entities, memory and JDBC stores."

dependencies {
    implementation(project(":strata-core"))
    annotationProcessor(project(":strata-processor"))
    runtimeOnly(project(":strata-pekko"))
    runtimeOnly(project(":strata-store-jdbc"))
    runtimeOnly(libs.logback)
}

application {
    mainClass.set("io.strata.bench.Bench")
    applicationDefaultJvmArgs = providers.gradleProperty("strata.jvm.args").get().split(" ").filter { it.isNotBlank() }
}

// ./gradlew :strata-bench:run -Pbench.store=jdbc  (any bench.* key from bench.conf)
tasks.named<JavaExec>("run") {
    providers.gradlePropertiesPrefixedBy("bench.").get().forEach { (k, v) -> systemProperty(k, v) }
    workingDir = layout.buildDirectory.dir("bench").get().asFile.also { it.mkdirs() }
}

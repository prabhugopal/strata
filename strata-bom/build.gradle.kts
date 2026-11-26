plugins { `java-platform` }

description = "Strata's bill of materials: one version for every Strata module."

@Suppress("UNCHECKED_CAST")
val published = rootProject.extra["published"] as List<String>

dependencies {
    constraints {
        published.forEach { api(project(":$it")) }
    }
}

publishing {
    publications { create<MavenPublication>("maven") { from(components["javaPlatform"]) } }
}

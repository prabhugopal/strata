plugins { `java-library` }

description = "Strata for Clojure: the defentity macro (immutable state, commands return [state result]) and loading entities from config."

sourceSets.main { resources.srcDir("src/main/clojure") }

dependencies {
    api(project(":strata-core"))
    api(libs.clojure)
}

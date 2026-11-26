// A standalone app, built the way users build theirs: only the Strata Gradle plugin. The plugin
// and Strata's modules come from this repository (included build) instead of a Maven repo.
pluginManagement { includeBuild("../..") }
includeBuild("../..")

rootProject.name = "accounts"

pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // OndolSaem client SDK (see docs/development.md, "OndolSaem SDK")
        maven("https://jitpack.io") { content { includeGroup("com.github.TaeHunKim") } }
    }
}
rootProject.name = "LocalSearchAnything"
include(":app", ":sdk")

// Develop against a sibling OndolSaem checkout (model host + client SDK): set `ondolsaem.dir=../ondolsaem`
// in local.properties and Gradle builds the SDK from that source instead of the published artifact.
// See docs/development.md, "OndolSaem SDK".
val ondolsaemDir = java.util.Properties().apply {
    file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("ondolsaem.dir")
// JitPack serves the SDK as com.github.TaeHunKim:ondolsaem:<tag> (a repository with one published artifact is
// renamed to the repository), so the substitution is explicit.
if (ondolsaemDir != null) includeBuild(ondolsaemDir) {
    dependencySubstitution { substitute(module("com.github.TaeHunKim:ondolsaem")).using(project(":sdk")) }
}

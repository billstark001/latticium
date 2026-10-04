pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        gradlePluginPortal()
    }
}

rootProject.name = "latticium"

include("dsl-core", "planning-core")

include(
    "versions:shared-mc-26.2",
    "versions:shared-mc-26.3",
    "versions:fabric-26.2",
    "versions:fabric-26.3",
    "versions:neoforge-26.2",
    "versions:neoforge-26.3",
    "integrations:litematica-fabric-26.2",
    "integrations:litematica-fabric-26.3",
    "integrations:forgematica-neoforge-26.2",
)

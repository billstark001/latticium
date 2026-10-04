plugins { id("net.fabricmc.fabric-loom") }

version = "0.1.0"

base.archivesName.set("latticium-fabric-26.3")

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.3")
    implementation(project(":versions:shared-mc-26.3"))
}

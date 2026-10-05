plugins { id("net.fabricmc.fabric-loom") }

base.archivesName.set("latticium-fabric-26.2")

loom {
    runs {
        named("client") {
            displayName = "Minecraft Client Latticium Fabric 26.2"
            generateRunConfig = true
            preferGradleTask = true
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.2")
    implementation(project(":versions:shared-mc-26.2"))
}

plugins { id("net.fabricmc.fabric-loom") }

base.archivesName.set("latticium-fabric-26.3")

loom {
    runs {
        named("client") {
            displayName = "Minecraft Client Latticium Fabric 26.3"
            generateRunConfig = true
            preferGradleTask = true
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.3")
    implementation(project(":versions:shared-mc-26.3"))
    implementation("me.shedaniel.cloth:cloth-config-fabric:26.3.159") {
        exclude(group = "net.fabricmc.fabric-api")
    }
    include("me.shedaniel.cloth:cloth-config-fabric:26.3.159")
    compileOnly("com.terraformersmc:modmenu:21.0.0")
    runtimeOnly("com.terraformersmc:modmenu:21.0.0")
}

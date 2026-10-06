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
    implementation("me.shedaniel.cloth:cloth-config-fabric:26.2.155") {
        exclude(group = "net.fabricmc.fabric-api")
    }
    include("me.shedaniel.cloth:cloth-config-fabric:26.2.155")
    compileOnly("com.terraformersmc:modmenu:20.0.3")
    runtimeOnly("com.terraformersmc:modmenu:20.0.3")
}

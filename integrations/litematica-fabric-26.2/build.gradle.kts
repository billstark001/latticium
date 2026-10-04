plugins { id("net.fabricmc.fabric-loom") }

version = "0.1.0"

base.archivesName.set("latticium-litematica-fabric-26.2")

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation(project(":versions:fabric-26.2"))
    implementation(project(":versions:shared-mc-26.2"))
    implementation(project(":planning-core"))
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.2")
    compileOnly(files(rootProject.file(".tmp/reference-mods/litematica-fabric-26.2-0.28.8.jar")))
    compileOnly(files(rootProject.file(".tmp/reference-mods/malilib-fabric-26.2-0.29.6.jar")))
    runtimeOnly(files(rootProject.file(".tmp/reference-mods/litematica-fabric-26.2-0.28.8.jar")))
    runtimeOnly(files(rootProject.file(".tmp/reference-mods/malilib-fabric-26.2-0.29.6.jar")))
}

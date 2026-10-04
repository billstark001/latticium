plugins { id("net.fabricmc.fabric-loom") }

version = "0.1.0"

base.archivesName.set("latticium-litematica-fabric-26.3")

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation(project(":versions:fabric-26.3"))
    implementation(project(":versions:shared-mc-26.3"))
    implementation(project(":planning-core"))
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.3")
    compileOnly(files(rootProject.file(".tmp/reference-mods/litematica-fabric-26.3-0.29.1.jar")))
    compileOnly(files(rootProject.file(".tmp/reference-mods/malilib-fabric-26.3-0.30.2.jar")))
    runtimeOnly(files(rootProject.file(".tmp/reference-mods/litematica-fabric-26.3-0.29.1.jar")))
    runtimeOnly(files(rootProject.file(".tmp/reference-mods/malilib-fabric-26.3-0.30.2.jar")))
}

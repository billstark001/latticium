plugins { id("net.fabricmc.fabric-loom") }

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    compileOnly("net.fabricmc:fabric-loader:0.19.5")
    compileOnly("me.shedaniel.cloth:cloth-config-fabric:26.3.159") { isTransitive = false }
    testImplementation("me.shedaniel.cloth:cloth-config-fabric:26.3.159") { isTransitive = false }
    api(project(":planning-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
}

plugins { id("net.fabricmc.fabric-loom") }

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    compileOnly("net.fabricmc:fabric-loader:0.19.5")
    api(project(":planning-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
}

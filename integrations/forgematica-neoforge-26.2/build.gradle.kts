plugins { id("net.neoforged.moddev") }

evaluationDependsOn(":versions:shared-mc-26.2")

evaluationDependsOn(":versions:neoforge-26.2")

val mainSource =
    project(":versions:neoforge-26.2")
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
        .get()
val sharedSource =
    project(":versions:shared-mc-26.2")
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
        .get()

version = "0.1.0"

base.archivesName.set("latticium-forgematica-neoforge-26.2")

neoForge {
    version = "26.2.0.88"
    runs {
        create("client") { client() }
    }
    mods {
        create("latticium_forgematica_bridge") { sourceSet(sourceSets.main.get()) }
        create("latticium") {
            sourceSet(mainSource)
            sourceSet(sharedSource)
        }
    }
}

dependencies {
    implementation(project(":versions:neoforge-26.2"))
    implementation(project(":versions:shared-mc-26.2"))
    implementation(project(":planning-core"))
    compileOnly(files(rootProject.file(".tmp/reference-mods/forgematica-0.5.0+mc26.2.jar")))
    compileOnly(files(rootProject.file(".tmp/reference-mods/mafglib-0.5.3+mc26.2.jar")))
    runtimeOnly(files(rootProject.file(".tmp/reference-mods/forgematica-0.5.0+mc26.2.jar")))
    runtimeOnly(files(rootProject.file(".tmp/reference-mods/mafglib-0.5.3+mc26.2.jar")))
    runtimeOnly(files(rootProject.file(".tmp/reference-mods/foxified-classtweaker-0.1.1.jar")))
}

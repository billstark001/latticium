plugins { id("net.neoforged.moddev") }

evaluationDependsOn(":versions:shared-mc-26.2")

val sharedSource =
    project(":versions:shared-mc-26.2")
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
        .get()

version = "0.1.0"

base.archivesName.set("latticium-neoforge-26.2")

neoForge {
    version = "26.2.0.88"
    runs {
        create("client") { client() }
    }
    mods {
        create("latticium") {
            sourceSet(sourceSets.main.get())
            sourceSet(sharedSource)
        }
    }
}

dependencies {
    implementation(project(":versions:shared-mc-26.2"))
}

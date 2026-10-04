plugins { id("net.neoforged.moddev") }

evaluationDependsOn(":versions:shared-mc-26.3")

val sharedSource =
    project(":versions:shared-mc-26.3")
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
        .get()

version = "0.1.0"

base.archivesName.set("latticium-neoforge-26.3")

neoForge {
    version = "26.3.0.48-beta"
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
    implementation(project(":versions:shared-mc-26.3"))
}

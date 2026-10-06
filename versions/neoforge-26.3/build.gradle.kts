plugins { id("net.neoforged.moddev") }

evaluationDependsOn(":versions:shared-mc-26.3")

val sharedSource =
    project(":versions:shared-mc-26.3")
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
        .get()

base.archivesName.set("latticium-neoforge-26.3")

neoForge {
    version = "26.3.0.48-beta"
    runs {
        create("client") {
            client()
            ideName.set("Minecraft Client Latticium NeoForge 26.3")
        }
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
    implementation("me.shedaniel.cloth:cloth-config-neoforge:26.3.159")
    jarJar("me.shedaniel.cloth:cloth-config-neoforge:26.3.159") {
        version {
            strictly("[26.3.159,26.4)")
            prefer("26.3.159")
        }
    }
}

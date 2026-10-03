plugins { `java-library` }
dependencies {
    api(project(":dsl-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
}

tasks.test { useJUnitPlatform { excludeTags("official") } }

tasks.register<Test>("officialTest") {
    description = "Validate the offline core against locally baked Mojang 26.2/26.3 reports"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("official") }
    systemProperty("latticium.officialRoot", rootProject.file(".tmp/minecraft-official").absolutePath)
    shouldRunAfter(tasks.test)
}

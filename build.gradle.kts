import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

plugins {
    base
    id("com.diffplug.spotless") version "8.10.3"
    id("net.fabricmc.fabric-loom") version "1.18.2" apply false
    id("net.neoforged.moddev") version "2.0.147" apply false
}

apply(from = "scripts/official-data.gradle.kts")

val referenceMods =
    mapOf(
        "litematica-fabric-26.2-0.28.8.jar" to
            Pair(
                "https://cdn.modrinth.com/data/bEpr0Arc/versions/CuniXtbo/litematica-fabric-26.2-0.28.8.jar",
                "d1c80538f3311b585c9311e59f14fd7fd3eb82288fe5afb399b2139cb35b42d24db7203379f2f61b50a610736335a86dbd395ca1795b001622e6b24dc6fe2a67",
            ),
        "litematica-fabric-26.3-0.29.1.jar" to
            Pair(
                "https://cdn.modrinth.com/data/bEpr0Arc/versions/ZqV316KY/litematica-fabric-26.3-0.29.1.jar",
                "45a8d468463afb97b3fc72efa168024ad69e670e77f5455ecf588583434a1787b32795edf5236579c32c828b02873efb39a4808caa08bbbaf1d8b6b090b160c3",
            ),
        "malilib-fabric-26.2-0.29.6.jar" to
            Pair(
                "https://cdn.modrinth.com/data/GcWjdA9I/versions/KvjmGjAV/malilib-fabric-26.2-0.29.6.jar",
                "0fab398f835d9c4736dac65510f707c77a60be6e6051bfd008225c20a5ff13e146296da258bee551d39fa28102b8854d77fa25b2ecb6b0f89fb3c874bac563db",
            ),
        "malilib-fabric-26.3-0.30.2.jar" to
            Pair(
                "https://cdn.modrinth.com/data/GcWjdA9I/versions/JX9bESec/malilib-fabric-26.3-0.30.2.jar",
                "8f17a5d70cb2f461aeb540f4a99de01c3351f8d3678d902d3d00cfe5d10c039fdc111c1079fe23aacb7936b793ad1cd045a8dc3a9e7926865820c254d92345b8",
            ),
        "forgematica-0.5.0+mc26.2.jar" to
            Pair(
                "https://cdn.modrinth.com/data/dCKRaeBC/versions/Y9EJDdgM/forgematica-0.5.0%2Bmc26.2.jar",
                "e68084f8591d0ee6235f41b0ccade2f027d6891f348343f25b9f86b4ce3ebef4d522dfe9ecab00d4428e4371b15f46d7f77db84fd1c24f65cf1b57d887cc5939",
            ),
        "mafglib-0.5.3+mc26.2.jar" to
            Pair(
                "https://cdn.modrinth.com/data/SKI34J7B/versions/a9U0m3ou/mafglib-0.5.3%2Bmc26.2.jar",
                "346ff060b775e2c9d78603510c34524e24d939c7cb716f3c7528c8b68a0ca12830ae532528825f91fd253a33488cacb3267834c5c853f190ed3c07f88531b78e",
            ),
        "foxified-classtweaker-0.1.1.jar" to
            Pair(
                "https://cdn.modrinth.com/data/bsJhdceD/versions/2sma6TjE/foxified-classtweaker-0.1.1.jar",
                "65c5ef94b5b6802ddf6d77dbb3c6ff0bf9edeed61a39de7867fa1f181ff1c27f3aeb0fefe842de59df7c20a29225a397a7f4c5bb7b463e059de411ea579ee52c",
            ),
    )

tasks.register("fetchReferenceMods") {
    group = "reference data"
    description = "Fetch pinned bridge compile APIs from Modrinth and verify SHA-512"
    doLast {
        val directory = rootProject.file(".tmp/reference-mods").toPath()
        Files.createDirectories(directory)
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        for ((name, reference) in referenceMods) {
            val (url, expected) = reference
            val destination = directory.resolve(name)
            fun hash(path: java.nio.file.Path): String =
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-512").digest(Files.readAllBytes(path))
                    )
            if (Files.exists(destination) && hash(destination) == expected) continue
            val uri = URI.create(url)
            require(uri.scheme == "https" && uri.host == "cdn.modrinth.com")
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            require(response.statusCode() == 200) { "Modrinth HTTP ${response.statusCode()}: $url" }
            val part = destination.resolveSibling("$name.part")
            try {
                Files.write(part, response.body())
                require(hash(part) == expected) { "SHA-512 mismatch: $name" }
                Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(part)
            }
        }
    }
}

repositories { mavenCentral() }

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**", ".tmp/**")
        ktfmt("0.63").kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**", ".tmp/**")
        ktfmt("0.63").kotlinlangStyle()
    }
}

tasks.register("formatAll") {
    group = "formatting"
    description = "Format Java and Gradle scripts"
    dependsOn(tasks.named("spotlessApply"), subprojects.map { "${it.path}:spotlessApply" })
}

tasks.register("checkSourceSize") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Require each formatted Java or Kotlin source file to stay within 500 lines"
    doLast {
        val oversized =
            fileTree(rootDir) {
                    include("**/*.java", "**/*.kt", "**/*.kts")
                    exclude("**/build/**", "**/.gradle/**", ".tmp/**")
                }
                .files
                .mapNotNull { file ->
                    val lines = file.useLines { it.count() }
                    if (lines > 500) "${file.relativeTo(rootDir)}: $lines lines" else null
                }
        if (oversized.isNotEmpty()) {
            throw GradleException("Source files exceed 500 lines:\n${oversized.joinToString("\n")}")
        }
    }
}

tasks.named("formatAll") { finalizedBy("checkSourceSize") }

tasks.register("lint") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Check Java and Gradle script sources"
    dependsOn(
        tasks.named("spotlessCheck"),
        subprojects.map { "${it.path}:lint" },
        "checkSourceSize",
    )
}

tasks.named("check") {
    dependsOn("lint", "testOfficialDataHelpers", subprojects.map { "${it.path}:test" })
}

subprojects {
    val coreModule = path == ":dsl-core" || path == ":planning-core"
    group = "io.github.billstark001.latticium"
    version = "0.1.0"
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")
    repositories { mavenCentral() }
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat("1.30.0")
            target("src/**/*.java")
        }
    }
    configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(if (coreModule) 21 else 25))
    }
    tasks.withType<JavaCompile>().configureEach {
        if (project.path.startsWith(":integrations:"))
            dependsOn(rootProject.tasks.named("fetchReferenceMods"))
        options.release.set(if (coreModule) 21 else 25)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf(
                if (coreModule) "-Xlint:all,-serial" else "-Xlint:all,-serial,-classfile",
                "-Werror",
            )
        )
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.12.2")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.register("lint") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Check Java formatting and compiler diagnostics"
        dependsOn(tasks.named("spotlessCheck"), tasks.withType<JavaCompile>())
    }
    tasks.named("check") { dependsOn("lint", rootProject.tasks.named("checkSourceSize")) }
    if (path.startsWith(":versions:fabric-") || path.startsWith(":versions:neoforge-")) {
        val mcVersion = name.substringAfterLast('-')
        val shared = ":versions:shared-mc-$mcVersion"
        val embeddedJson = configurations.create("embeddedJson")
        dependencies.add("embeddedJson", "com.fasterxml.jackson.core:jackson-databind:2.19.2")
        tasks.named<Jar>("jar") {
            dependsOn("$shared:jar", ":planning-core:jar", ":dsl-core:jar")
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            from({
                listOf(shared, ":planning-core", ":dsl-core").map { module ->
                    zipTree(project(module).tasks.named<Jar>("jar").get().archiveFile.get().asFile)
                }
            })
            from({ embeddedJson.files.map(::zipTree) })
            exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        }
    }
}

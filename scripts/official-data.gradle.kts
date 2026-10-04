import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Duration
import java.util.Collections
import java.util.zip.ZipFile

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("com.fasterxml.jackson.core:jackson-databind:2.19.2") }
}

val officialRoot =
    rootProject.layout.projectDirectory.dir(".tmp/minecraft-official").asFile.toPath()
val versions =
    (findProperty("officialVersions") as String? ?: "26.2,26.3")
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

require(versions.isNotEmpty()) { "officialVersions must contain at least one version" }

val versionComponent = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

require(versions.all { versionComponent.matches(it) && it != ".." }) {
    "officialVersions entries must be single directory names"
}

val mapper = ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
val allowedHosts = setOf("piston-meta.mojang.com", "piston-data.mojang.com")
val manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

fun JsonNode.names(): List<String> = fieldNames().asSequence().toList()

fun sha1(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-1")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun officialUri(raw: String): URI {
    val uri = URI.create(raw)
    require(uri.scheme == "https" && uri.host in allowedHosts) { "Nonofficial download URL: $raw" }
    return uri
}

fun fetch(raw: String, path: Path, expectedSha1: String? = null) {
    var uri = officialUri(raw)
    if (Files.exists(path) && (expectedSha1 == null || sha1(path) == expectedSha1)) {
        println("cached $path")
        return
    }
    Files.createDirectories(path.parent)
    val part = path.resolveSibling("${path.fileName}.part")
    try {
        var response: HttpResponse<java.io.InputStream>
        var redirects = 0
        while (true) {
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build()
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val status = response.statusCode()
            if (status !in setOf(301, 302, 303, 307, 308)) break
            response.body().close()
            require(++redirects <= 5) { "Too many redirects: $raw" }
            val location = response.headers().firstValue("location").orElseThrow()
            uri = officialUri(uri.resolve(location).toString())
        }
        response.body().use { input ->
            require(response.statusCode() == 200) { "HTTP ${response.statusCode()}: $uri" }
            Files.newOutputStream(part).use { input.copyTo(it) }
        }
        require(expectedSha1 == null || sha1(part) == expectedSha1) { "SHA-1 mismatch: $path" }
        Files.move(part, path, StandardCopyOption.REPLACE_EXISTING)
        println("fetched $path")
    } finally {
        Files.deleteIfExists(part)
    }
}

tasks.register("testOfficialDataHelpers") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Check official URL validation and nested tag resolution without downloads"
    doLast {
        check(officialUri("https://piston-meta.mojang.com/a").host in allowedHosts)
        for (url in
            listOf(
                "http://piston-meta.mojang.com/a",
                "https://piston-meta.mojang.com.evil.example/a",
            )) check(runCatching { officialUri(url) }.isFailure)
        val nested =
            mapOf(
                "minecraft:base" to mapper.readTree("""{"values":["minecraft:stone"]}"""),
                "minecraft:outer" to
                    mapper.readTree(
                        """{"values":["#minecraft:base",
                {"id":"minecraft:missing","required":false},"minecraft:dirt"]}"""
                    ),
            )
        check(
            resolveTags(nested, setOf("minecraft:stone", "minecraft:dirt"), "block")
                .getValue("minecraft:outer") == listOf("minecraft:dirt", "minecraft:stone")
        )
        val cyclic =
            mapOf(
                "minecraft:a" to mapper.readTree("""{"values":["#minecraft:b"]}"""),
                "minecraft:b" to mapper.readTree("""{"values":["#minecraft:a"]}"""),
            )
        check(runCatching { resolveTags(cyclic, emptySet(), "block") }.isFailure)
        val missing =
            mapOf("minecraft:bad" to mapper.readTree("""{"values":["minecraft:missing"]}"""))
        check(runCatching { resolveTags(missing, emptySet(), "block") }.isFailure)
    }
}

fun resolveTags(
    definitions: Map<String, JsonNode>,
    universe: Set<String>,
    kind: String,
): Map<String, List<String>> {
    val resolved = mutableMapOf<String, List<String>>()
    val active = mutableSetOf<String>()
    fun expand(tag: String): List<String> {
        resolved[tag]?.let {
            return it
        }
        require(tag !in active) { "Cyclic $kind tag: $tag" }
        val definition = definitions[tag] ?: error("Missing $kind tag: $tag")
        active.add(tag)
        try {
            val values = mutableSetOf<String>()
            for (member in definition.required("values")) {
                val required = !member.isObject || member.path("required").asBoolean(true)
                val id = if (member.isObject) member.required("id").asText() else member.asText()
                if (id.startsWith("#")) {
                    val reference = id.drop(1)
                    if (reference in definitions) values.addAll(expand(reference))
                    else if (required) error("Missing required $kind tag: $reference")
                } else if (id in universe) values.add(id)
                else if (required) error("Missing required $kind ID: $id")
            }
            return values.sorted().also { resolved[tag] = it }
        } finally {
            active.remove(tag)
        }
    }
    definitions.keys.forEach(::expand)
    return resolved
}

tasks.register("fetchOfficialMinecraft") {
    group = "official data"
    description = "Fetch and verify Mojang 26.2/26.3 metadata and JARs"
    doLast {
        val manifestPath = officialRoot.resolve("version_manifest_v2.json")
        if (findProperty("refreshOfficialManifest") == "true") Files.deleteIfExists(manifestPath)
        fetch(manifestUrl, manifestPath)
        val manifest = mapper.readTree(manifestPath.toFile())
        val available = manifest.required("versions").associateBy { it.required("id").asText() }
        for (version in versions) {
            val entry = available[version] ?: error("Version absent from Mojang manifest: $version")
            val directory = officialRoot.resolve(version)
            val metadataPath = directory.resolve("version.json")
            fetch(entry.required("url").asText(), metadataPath, entry.required("sha1").asText())
            val metadata = mapper.readTree(metadataPath.toFile())
            val index = metadata.required("assetIndex")
            fetch(
                index.required("url").asText(),
                directory.resolve("asset-index.json"),
                index.required("sha1").asText(),
            )
            val downloads = metadata.required("downloads")
            val names =
                if (findProperty("officialClientOnly") == "true") listOf("client")
                else listOf("client", "server")
            for (name in names) {
                val download = downloads.required(name)
                fetch(
                    download.required("url").asText(),
                    directory.resolve("$name.jar"),
                    download.required("sha1").asText(),
                )
            }
            val receipt =
                mapOf(
                    "version" to version,
                    "javaMajor" to
                        metadata.required("javaVersion").required("majorVersion").asInt(),
                    "metadataSha1" to entry.required("sha1").asText(),
                    "assetIndexSha1" to index.required("sha1").asText(),
                    "downloads" to
                        downloads
                            .names()
                            .filter { it in setOf("client", "server") }
                            .associateWith {
                                mapOf(
                                    "sha1" to downloads.required(it).required("sha1").asText(),
                                    "size" to downloads.required(it).required("size").asLong(),
                                )
                            },
                )
            Files.writeString(
                directory.resolve("receipt.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(receipt) + "\n",
            )
            println("verified $version")
        }
    }
}

fun ensureReports(
    version: String,
    directory: Path,
    server: Path,
    serverSha1: String,
    java: String,
): Path {
    require(directory.toRealPath().parent == officialRoot.toRealPath()) {
        "Official version directory is outside the data root: $directory"
    }
    val generated = directory.resolve("generated")
    val reports = generated.resolve("reports")
    val stamp = generated.resolve("server.sha1")
    if (
        Files.isRegularFile(reports.resolve("blocks.json")) &&
            Files.isRegularFile(reports.resolve("registries.json")) &&
            Files.isRegularFile(stamp) &&
            Files.readString(stamp).trim() == serverSha1
    ) {
        println("cached reports $version")
        return reports
    }
    if (Files.exists(generated)) {
        Files.walkFileTree(
            generated,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
    Files.createDirectories(generated)
    val log = directory.resolve("data-generator.log")
    val process =
        ProcessBuilder(
                java,
                "-DbundlerMainClass=net.minecraft.data.Main",
                "-DbundlerRepoDir=${directory.resolve("bundler")}",
                "-jar",
                server.toString(),
                "--reports",
                "--output",
                generated.toString(),
            )
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
    require(process.waitFor() == 0) { "Mojang data generator failed for $version; see $log" }
    require(
        Files.isRegularFile(reports.resolve("blocks.json")) &&
            Files.isRegularFile(reports.resolve("registries.json"))
    ) {
        "Missing generated reports for $version"
    }
    Files.writeString(stamp, "$serverSha1\n")
    return reports
}

fun <T> withBundledServerJar(server: Path, version: String, action: (ZipFile) -> T): T {
    val innerFile = Files.createTempFile("latticium-server-", ".jar")
    try {
        ZipFile(server.toFile()).use { outer ->
            val entry =
                Collections.list(outer.entries()).firstOrNull {
                    it.name.startsWith("META-INF/versions/") && it.name.endsWith(".jar")
                } ?: error("Bundled server JAR missing for $version")
            outer.getInputStream(entry).use { input ->
                Files.newOutputStream(innerFile).use { input.copyTo(it) }
            }
        }
        return ZipFile(innerFile.toFile()).use(action)
    } finally {
        Files.deleteIfExists(innerFile)
    }
}

fun readTags(
    inner: ZipFile,
    names: List<String>,
    universes: Map<String, List<String>>,
    mapper: ObjectMapper,
): Map<String, Map<String, List<String>>> {
    val prefixes =
        mapOf(
            "block" to "tags/block/",
            "item" to "tags/item/",
            "fluid" to "tags/fluid/",
            "biome" to "tags/worldgen/biome/",
        )
    return prefixes.mapValues { (kind, prefix) ->
        val fullPrefix = "data/minecraft/$prefix"
        val definitions =
            names
                .filter { it.startsWith(fullPrefix) && it.endsWith(".json") }
                .associate { name ->
                    val id = "minecraft:" + name.removePrefix(fullPrefix).removeSuffix(".json")
                    id to inner.getInputStream(inner.getEntry(name)).use(mapper::readTree)
                }
        resolveTags(definitions, universes.getValue(kind).toSet(), kind)
    }
}

tasks.register("bakeOfficialMinecraft") {
    group = "official data"
    description = "Generate offline registry catalogs from downloaded Mojang server bundles"
    doLast {
        val java = findProperty("officialJava") as String? ?: "java"
        for (version in versions) {
            val directory = officialRoot.resolve(version)
            val server = directory.resolve("server.jar")
            require(Files.isRegularFile(server)) { "Run fetchOfficialMinecraft first: $server" }
            val serverSha1 = sha1(server)
            val receipt = mapper.readTree(directory.resolve("receipt.json").toFile())
            require(
                serverSha1 ==
                    receipt.required("downloads").required("server").required("sha1").asText()
            ) {
                "Server JAR SHA-1 does not match Mojang metadata: $version"
            }
            val reports = ensureReports(version, directory, server, serverSha1, java)

            val blocksReport = mapper.readTree(reports.resolve("blocks.json").toFile())
            val registriesReport = mapper.readTree(reports.resolve("registries.json").toFile())
            val kinds =
                mapOf(
                    "block" to "minecraft:block",
                    "item" to "minecraft:item",
                    "fluid" to "minecraft:fluid",
                )
            withBundledServerJar(server, version) { inner ->
                val names = Collections.list(inner.entries()).map { it.name }
                val biomes =
                    names
                        .filter {
                            it.startsWith("data/minecraft/worldgen/biome/") && it.endsWith(".json")
                        }
                        .map {
                            "minecraft:" +
                                it.removePrefix("data/minecraft/worldgen/biome/")
                                    .removeSuffix(".json")
                        }
                        .sorted()
                val universes = mutableMapOf<String, List<String>>()
                for ((kind, registry) in kinds) universes[kind] =
                    registriesReport.required(registry).required("entries").names().sorted()
                universes["biome"] = biomes
                require(blocksReport.names().toSet() == universes.getValue("block").toSet()) {
                    "Block report and registry dump disagree for $version"
                }
                val tags = readTags(inner, names, universes, mapper)
                val blocks =
                    blocksReport.names().associateWith { id ->
                        val definition = blocksReport.required(id)
                        mapOf(
                            "properties" to
                                (definition.get("properties") ?: mapper.createObjectNode()),
                            "states" to
                                definition.required("states").map {
                                    it.get("properties") ?: mapper.createObjectNode()
                                },
                        )
                    }
                val catalog =
                    mapOf(
                        "schema" to 1,
                        "version" to version,
                        "sourceSha1" to
                            mapOf(
                                "serverJar" to serverSha1,
                                "blocksReport" to sha1(reports.resolve("blocks.json")),
                                "registriesReport" to sha1(reports.resolve("registries.json")),
                            ),
                        "universes" to universes,
                        "blocks" to blocks,
                        "tags" to tags,
                    )
                val output = directory.resolve("catalog.json")
                Files.writeString(output, mapper.writeValueAsString(catalog) + "\n")
                val summary =
                    mapOf(
                        "version" to version,
                        "blocks" to universes.getValue("block").size,
                        "states" to blocks.values.sumOf { (it["states"] as List<*>).size },
                        "items" to universes.getValue("item").size,
                        "fluids" to universes.getValue("fluid").size,
                        "biomes" to biomes.size,
                        "tags" to tags.mapValues { it.value.size },
                        "catalogSha1" to sha1(output),
                    )
                Files.writeString(
                    directory.resolve("catalog-summary.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n",
                )
                println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary))
            }
        }
    }
}

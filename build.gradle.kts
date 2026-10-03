plugins {
    base
    id("com.diffplug.spotless") version "8.10.3"
}

apply(from = "scripts/official-data.gradle.kts")

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
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")
    repositories { mavenCentral() }
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat("1.28.0")
            target("src/**/*.java")
        }
    }
    configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
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
}

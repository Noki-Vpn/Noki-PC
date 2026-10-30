import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "dev.nucleusframework"
version = "2.5.18-noki.2"
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("nucleus-tao"))

val preparedSources = layout.buildDirectory.dir("upstream")
val prepareSources = tasks.register<Exec>("prepareSources") {
    inputs.files("prepare_sources.py", "nucleus.decorated-window-tao-2.5.18-sources.jar",
        "patches/windows-scene-dispatcher.patch", "patches/SceneMainDispatcher.kt",
        "windows-gl-2.5.18-sources.zip", "patches/windows-gl-idle-display.patch")
    outputs.dir(preparedSources)
    commandLine("python", "-X", "utf8", layout.projectDirectory.file("prepare_sources.py").asFile.absolutePath,
        preparedSources.get().asFile.absolutePath,
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get().metadata.installationPath.asFile.absolutePath)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    sourceSets.main { java.setSrcDirs(listOf(preparedSources.map { it.dir("java") })) }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Preserve the published internal ABI used by the other pinned Nucleus modules.
        moduleName.set("Nucleus_decorated-window-tao")
    }
    sourceSets.main {
        // Kotlin must resolve the generated Java accessor before javac compiles it.
        kotlin.setSrcDirs(listOf(preparedSources.map { it.dir("kotlin") }, preparedSources.map { it.dir("java") }))
    }
}

dependencies {
    api("dev.nucleusframework:nucleus.decorated-window-core:2.5.18")
    implementation("dev.nucleusframework:nucleus.core-runtime:2.5.18")
    implementation("dev.nucleusframework:nucleus.energy-manager:2.5.18")
    implementation("org.jetbrains.compose.desktop:desktop:1.12.0")
    // The published module compiles its optional hot-reload bridge against these APIs.
    for (artifact in listOf("hot-reload-agent", "hot-reload-core", "hot-reload-orchestration", "hot-reload-devtools-api")) {
        compileOnly("org.jetbrains.compose.hot-reload:$artifact:1.2.0")
    }
    testImplementation("junit:junit:4.13.2")
}

tasks.named("compileKotlin") { dependsOn(prepareSources) }
tasks.named("compileJava") { dependsOn(prepareSources) }
tasks.named<ProcessResources>("processResources") {
    dependsOn(prepareSources)
    from(preparedSources.map { it.dir("resources") })
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("nucleus.decorated-window-tao")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    metaInf {
        from(rootProject.file("vendor/licenses/Nucleus/THIRD_PARTY_NOTICES.md"))
        from(rootProject.file("vendor/licenses/Nucleus/LICENSE"))
        from(rootProject.file("vendor/licenses/Nucleus/licenses")) { into("licenses") }
    }
}

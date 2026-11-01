import dev.nucleusframework.desktop.application.dsl.TargetFormat
import java.awt.image.BufferedImage
import java.awt.RenderingHints
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.0"
    id("dev.nucleusframework") version "2.5.18"
}

group = "com.noki"
version = "1.7.6"

val desktopTarget = providers.gradleProperty("desktopTarget").orElse("linux").get()
require(desktopTarget in setOf("linux", "windows")) { "desktopTarget must be linux or windows" }
val linuxHost = desktopTarget == "linux"
val desktopHostSources = layout.buildDirectory.dir("generated/desktop-host")
val generateDesktopHost = tasks.register("generateDesktopHost") {
    inputs.property("linuxHost", linuxHost)
    outputs.dir(desktopHostSources)
    doLast {
        desktopHostSources.get().file("com/noki/desktop/DesktopBuild.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package com.noki.desktop\ninternal object DesktopBuild { const val IS_LINUX = $linuxHost; const val PLATFORM = \"${if (linuxHost) "linux" else "windows"}\" }\n")
        }
    }
}
kotlin {
    jvmToolchain(21)
    sourceSets.main { kotlin.srcDir(desktopHostSources) }
}
tasks.named("compileKotlin") { dependsOn(generateDesktopHost) }

tasks.named<Jar>("jar") { manifest.attributes["Implementation-Version"] = project.version.toString() }
val desktopIcons = layout.buildDirectory.dir("generated/desktop-icons")
val generateDesktopIcons = tasks.register("generateDesktopIcons") {
    val source = layout.projectDirectory.dir("src/main/resources/noki/desktop")
    val supplied = mapOf(32 to source.file("app-32.png"), 40 to source.file("app-40.png"), 48 to source.file("app-48.png"), 256 to source.file("app.png"))
    inputs.files(supplied.values)
    outputs.dir(desktopIcons)
    doLast {
        val output = desktopIcons.get().asFile.apply { mkdirs() }
        val logoFile = source.file("app.png").asFile
        val logo = javax.imageio.ImageIO.read(logoFile)
        val sizes = listOf(16, 20, 24, 32, 40, 48, 64, 96, 128, 256)
        val frames = sizes.map { size ->
            supplied[size]?.let { return@map it.asFile.readBytes() }
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.drawImage(logo, 0, 0, size, size, null)
            } finally { graphics.dispose() }
            ByteArrayOutputStream().use { bytes ->
                check(javax.imageio.ImageIO.write(image, "png", bytes))
                image.flush()
                bytes.toByteArray()
            }
        }
        logo.flush()
        val headerSize = 6 + 16 * sizes.size
        val ico = ByteBuffer.allocate(headerSize + frames.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        ico.putShort(0).putShort(1).putShort(sizes.size.toShort())
        var offset = headerSize
        for ((index, size) in sizes.withIndex()) {
            ico.put((size % 256).toByte()).put((size % 256).toByte()).put(0).put(0)
                .putShort(1).putShort(32).putInt(frames[index].size).putInt(offset)
            offset += frames[index].size
        }
        frames.forEach { ico.put(it) }
        output.resolve("app.ico").writeBytes(ico.array())
    }
}
// Average numeric 16-bit heights before repacking RG; averaging the two bytes as colours corrupts carries.
val generateEarthWindowAtlas = tasks.register("generateEarthWindowAtlas") {
    val original = layout.projectDirectory.file("src/main/resources/noki/desktop/earth/relief.png")
    val output = layout.buildDirectory.file("generated/earth-window/noki/desktop/earth/relief-4k.png")
    inputs.file(original)
    outputs.file(output)
    doLast {
        val source = javax.imageio.ImageIO.read(original.asFile)
        check(source.width == 8192 && source.height == 4096)
        val target = BufferedImage(4096, 2048, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until target.height) for (x in 0 until target.width) {
            var height = 0; var coast = 0
            for (dy in 0..1) for (dx in 0..1) {
                val pixel = source.getRGB(x * 2 + dx, y * 2 + dy)
                height += pixel ushr 8 and 65535
                coast += pixel and 255
            }
            target.setRGB(x, y, (255 shl 24) or (((height + 2) / 4) shl 8) or ((coast + 2) / 4))
        }
        output.get().asFile.apply { parentFile.mkdirs(); check(javax.imageio.ImageIO.write(target, "png", this)) }
        source.flush(); target.flush()
    }
}
val generateCountryGeography = tasks.register("generateCountryGeography") {
    val source = layout.projectDirectory.file("src/main/resources/noki/desktop/earth/countries.json")
    val output = layout.buildDirectory.file("generated/geography/noki/desktop/earth/countries.bin")
    inputs.file(source)
    outputs.file(output)
    doLast {
        val json = groovy.json.JsonSlurper().parse(source.asFile) as Map<*, *>
        val target = output.get().asFile.apply { parentFile.mkdirs() }
        DataOutputStream(target.outputStream().buffered()).use { data ->
            fun point(row: Any?) {
                val coordinates = row as List<*>
                data.writeFloat(Math.toRadians((coordinates[0] as Number).toDouble()).toFloat())
                data.writeFloat(Math.toRadians((coordinates[1] as Number).toDouble()).toFloat())
                data.writeFloat((coordinates[2] as Number).toFloat())
            }
            data.writeInt(1)
            val labels = json["countries"] as Map<*, *>
            data.writeInt(labels.size)
            for ((code, coordinates) in labels.entries.sortedBy { it.key.toString() }) {
                data.writeUTF(code.toString()); point(coordinates)
            }
            val borders = json["outlines"] as Map<*, *>
            data.writeInt(borders.size)
            for ((code, value) in borders.entries.sortedBy { it.key.toString() }) {
                val rings = value as List<*>
                data.writeUTF(code.toString()); data.writeInt(rings.size)
                for (ring in rings) {
                    val coordinates = ring as List<*>
                    data.writeInt(coordinates.size)
                    coordinates.forEach(::point)
                }
            }
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateEarthWindowAtlas, generateCountryGeography, generateDesktopIcons)
    val appVersionFile = layout.buildDirectory.file("generated/app-version/version.txt")
    inputs.property("appVersion", project.version.toString())
    doFirst { appVersionFile.get().asFile.apply { parentFile.mkdirs(); writeText(project.version.toString()) } }
    from(appVersionFile) { into("noki/desktop") }
    if (linuxHost) { dependsOn("prepareVpnRuntime"); from(layout.buildDirectory.file("runtime/linux-x64/linux-core.properties")) { into("noki/desktop") } }
    // Authoring sources stay available to parity tests, not in Native Image's permanent image heap.
    exclude("noki/desktop/earth/relief.png", "noki/desktop/earth/countries.json")
    from(layout.buildDirectory.dir("generated/earth-window"))
    from(layout.buildDirectory.dir("generated/geography"))
}

tasks.named<ProcessResources>("processTestResources") {
    from("src/test/fixtures")
    from("src/main/resources") { include("noki/desktop/earth/relief.png", "noki/desktop/earth/countries.json") }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.json:json:20240303")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("io.github.kyant0:backdrop-desktop:2.0.1")
    implementation("dev.nucleusframework:nucleus.nucleus-application:2.5.18")
    add(if (linuxHost) "implementation" else "compileOnly", "dev.nucleusframework:composenativetray:2.1.6") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }
    implementation("dev.nucleusframework:nucleus.decorated-window-tao:2.5.18")
    implementation("dev.nucleusframework:nucleus.graalvm-runtime:2.5.18")
    if (linuxHost) implementation("org.graalvm.sdk:nativeimage:25.0.1") { isTransitive = false }
    else compileOnly("org.graalvm.sdk:nativeimage:25.0.1") { isTransitive = false }
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.re2j:re2j:1.8")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

if (!linuxHost) configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("dev.nucleusframework:nucleus.decorated-window-tao:2.5.18"))
            .using(project(":nucleus-tao"))
            .because("Windows scene dispatch must not request unchanged frames (upstream #754)")
    }
}

nucleus {
    application {
        mainClass = "com.noki.desktop.MainKt"
        jvmArgs("-Xms32m", "-Xmx512m", "-XX:G1PeriodicGCInterval=30000")
        graalvm {
            isEnabled.set(!linuxHost)
            imageName.set("Noki")
            maxHeapSize.set("512m")
            nativeImageConfigBaseDir.set(layout.projectDirectory.dir("graalvm"))
            buildArgs.addAll("-J-Xmx6g", "--parallelism=3",
                "-H:IncludeLocales=ru,en",
                "-R:MaxHeapFree=4m",
                "-H:ExcludeResources=nucleus/native/.*",
                "-H:ExcludeResources=skiko-windows-x64[.]dll",
                "-H:ExcludeResources=icudtl[.]dat")
            toolchain { version.set("25.0.1") }
        }
        nativeDistributions {
            if (linuxHost) targetFormats(TargetFormat.Deb, TargetFormat.Rpm) else targetFormats(TargetFormat.Msi)
            packageName = "Noki"
            appName = if (linuxHost) "Noki" else "Noki Vpn"
            packageVersion = project.version.toString()
            description = "Noki VPN"
            vendor = "Noki"
            windows { iconFile.set(desktopIcons.map { it.file("app.ico") }) }
            modules("java.naming", "jdk.crypto.ec", "java.management", "jdk.unsupported", "java.desktop", "jdk.security.auth")
            appResourcesRootDir.set(layout.buildDirectory.dir(if (linuxHost) "linux-app-resources" else "runtime"))
        }
    }
}

tasks.matching { it.name == "generateGraalvmWindowsResources" }.configureEach { dependsOn(generateDesktopIcons) }

val prepareVpnRuntime = tasks.register<Exec>("prepareVpnRuntime") {
    if (linuxHost) commandLine("python3", file("scripts/prepare-runtime-linux.py").absolutePath)
    else commandLine("powershell.exe", "-NoProfile", "-File", file("scripts/prepare-runtime.ps1").absolutePath)
    if (linuxHost) doLast { layout.buildDirectory.dir("linux-app-resources").get().asFile.mkdirs() }
}

val prepareUpdater = tasks.register<Exec>("prepareUpdater") {
    onlyIf { !linuxHost }
    commandLine("powershell.exe", "-NoProfile", "-File", file("scripts/prepare-updater.ps1").absolutePath)
}

tasks.matching { it.name in setOf("test", "run", "createDistributable", "packageMsi", "prepareAppResources") }.configureEach {
    dependsOn(prepareVpnRuntime)
    dependsOn(prepareUpdater)
}

tasks.named<Test>("test") {
    if (!linuxHost) dependsOn(":nucleus-tao:test")
    systemProperty("noki.test.classpath", sourceSets["test"].runtimeClasspath.asPath)
}

// Native Image emits the required JDK libraries and its own java/jvm shims beside the EXE.
if (!linuxHost) tasks.withType<Copy>().matching { it.name == "copyGraalvmBinaryToOutput" }.configureEach {
    dependsOn(prepareUpdater)
    from(layout.buildDirectory.file("tools/velopack_libc_win_x64_msvc.dll"))
    from(tasks.named("nativeImageCompile")) { include("*.dll") }
    // Both existing loaders already search the distribution's bin/ directory.
    val nativeLibraries = configurations.runtimeClasspath.map { runtime ->
        runtime.filter { it.name.startsWith("nucleus.") && it.extension == "jar" }.flatMap { jar ->
            zipTree(jar).matching { include("nucleus/native/win32-x64/*.dll") }.files
        }
    }
    from(nativeLibraries) { into("bin") }
    doFirst {
        val files = nativeLibraries.get()
        check(files.isNotEmpty() && files.map { it.name.lowercase(Locale.ROOT) }.toSet().size == files.size) {
            "Missing or colliding Windows Nucleus native libraries"
        }
    }
    from(tasks.named<Copy>("copyGraalvmJawtToBin").map { it.source.singleFile.parentFile.parentFile }) {
        include("legal/**", "LICENSE*.txt", "THIRD_PARTY_LICENSE.txt")
        into("licenses/GraalVM")
    }
}
// The plugin's JDK copies would overwrite the generated shims with HotSpot DLLs.
tasks.matching { it.name in setOf("copyGraalvmAwtDlls", "copyGraalvmJvmDll") }.configureEach {
    enabled = false
}

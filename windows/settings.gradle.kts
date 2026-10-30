pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "NokiDesktop"
val desktopTarget = providers.gradleProperty("desktopTarget").orNull
    ?: "windows"
if (desktopTarget == "windows") {
    include(":nucleus-tao")
    project(":nucleus-tao").projectDir = file("vendor/nucleus-tao")
}

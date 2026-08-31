pluginManagement {
    repositories {
        // Loom's plugin marker + implementation jar live on Fabric's own Maven.
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        // Stonecutter: the marker is on the Plugin Portal, the real jar is on
        // Kikugie's Maven. Include both so resolution can never half-fail.
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        mavenCentral()
        gradlePluginPortal()
    }

    // Loom's version is declared once, in gradle.properties, so the shared
    // per-node build.gradle.kts can apply it without repeating a literal and
    // there is exactly one place to bump it.
    val loom_version: String by settings

    plugins {
        id("net.fabricmc.fabric-loom") version loom_version
    }
}

plugins {
    // Lets a machine without a JDK 25 auto-provision one for the java toolchain
    // declared in build.gradle.kts.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.7"
}

stonecutter {
    create(rootProject) {
        // Node name == Minecraft version string. Both nodes are >= 26.1, i.e.
        // unobfuscated / post-Yarn, so no loom-back-compat layer is needed.
        versions("26.1.2", "26.2")
        vcsVersion = "26.2"
    }
}

rootProject.name = "SkyPrism"

plugins {
    id("dev.kikugie.stonecutter")
}

// Which node's preprocessed sources are currently expanded into src/ on disk.
stonecutter active "26.2"

// Aggregate task: builds every registered Minecraft version.
// Stonecutter 0.9.x removed the old `chiseled` task type / `registerChiseled`
// infix; the replacement is stonecutter.tasks.named(<name>), a lazy map of the
// same-named task across every node, which you depend on from your own task.
tasks.register("buildAllVersions") {
    group = "build"
    description = "Builds SkyPrism for every registered Minecraft version."
    dependsOn(stonecutter.tasks.named("build"))
}

tasks.register("testAllVersions") {
    group = "verification"
    description = "Runs the unit tests for every registered Minecraft version."
    dependsOn(stonecutter.tasks.named("test"))
}

// The Minecraft-aware suite, across nodes. build.gradle.kts deliberately keeps `mcTest`
// out of `check` so the fast unit suite stays fast, which had the side effect that
// src/mcTest -- the ONLY automated coverage of the adapters whose behaviour is
// cross-version sensitive (LegacyText's legacy-colour table, ComponentRewriter,
// LevelNameMemo, the ChatRouter markers) -- was run by no command anyone would plausibly
// type on both nodes. `clean buildAllVersions testAllVersions` stayed fully green while
// the one test that could catch a per-node colour-table drift never executed.
tasks.register("mcTestAllVersions") {
    group = "verification"
    description = "Runs the Minecraft-aware tests for every registered Minecraft version."
    dependsOn(stonecutter.tasks.named("mcTest"))
}

// The root project applies no java/base plugin, so `gradlew clean` used to clean
// only the version nodes and leave last build's collected jars sitting in
// <root>/build/libs/<mc>/. Give the root a clean of its own so
// `gradlew clean buildAllVersions` really does regenerate both jars.
tasks.register<Delete>("clean") {
    group = "build"
    description = "Deletes the collected per-version jars under <root>/build."
    delete(layout.buildDirectory)
}

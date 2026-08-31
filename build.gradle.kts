import org.gradle.api.artifacts.component.ModuleComponentIdentifier

// Central Stonecutter build script. Every version node (versions/26.1.2 and
// versions/26.2) is configured by this same file.
//
// Minecraft 26.1+ ships UNOBFUSCATED: Yarn is retired, there is no mappings()
// line, the Loom plugin id is "net.fabricmc.fabric-loom", mod dependencies use
// plain implementation/compileOnly (not modImplementation), and the output task
// is `jar` (there is no remapJar). Java 25 is mandatory.
plugins {
    id("net.fabricmc.fabric-loom")
}

// Node id == Minecraft version string (set by versions(...) in settings.gradle.kts).
val mcVersion: String = stonecutter.current.version

// Per-node dependency versions live in versions/<mc>/gradle.properties (the
// Stonecutter idiom), so bumping Fabric API is a properties edit rather than a
// build-script edit. Fail loudly if a node forgets to declare one.
val fabricApiVersion: String = findProperty("fabric_api_version") as String?
    ?: throw GradleException(
        "Minecraft $mcVersion has no fabric_api_version: add it to " +
            "versions/$mcVersion/gradle.properties (every Stonecutter node must declare its own)."
    )

// YetAnotherConfigLib and ModMenu are OPTIONAL at runtime but ship one artifact
// per Minecraft version, so like fabric_api_version they are declared per node in
// versions/<mc>/gradle.properties. Unlike Fabric API a missing entry is NOT fatal:
// the mod is fully functional with no settings screen (the real config store is the
// core's ConfigCodec), so a node that has not yet been given a compatible YACL build
// simply compiles the screen out of the dev runtime rather than failing the build.
val yaclVersion: String? = findProperty("yacl_version") as String?
val modmenuVersion: String? = findProperty("modmenu_version") as String?

group = property("mod.group") as String
version = "${property("mod.version")}+$mcVersion"
base.archivesName = property("mod.id") as String

repositories {
    mavenCentral()
    // Optional config-GUI dependencies. Both are compileOnly + localRuntime, so
    // neither is bundled into the jar nor required for the mod to load.
    maven("https://maven.isxander.dev/releases") { name = "Xander" }
    maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
}

// Detached configuration holding only the unit-test framework. Deliberately
// extends nothing, so Loom's Minecraft artifacts can never leak into it.
val unitTest: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Optional at runtime, quarantined at compile time. compileOnly (not
    // implementation) because neither library is shipped inside our jar and neither
    // is required to load: every dev.isxander import in the mod lives in the single
    // package-private class com.skyprism.mc.gui.SkyPrismConfigScreen, reached only
    // through the Screen-typed ConfigGui facade, so with YACL absent the JVM never
    // links the quarantined class. 26.x is unobfuscated, so plain compileOnly is
    // correct -- there is nothing to remap and no modImplementation.
    //
    // localRuntime (not modLocalRuntime, for the same no-remapping reason) puts both
    // jars into the dev client only, so `runClient` can actually open the screen.
    if (yaclVersion != null) {
        compileOnly("dev.isxander:yet-another-config-lib:$yaclVersion") {
            // config/v3 is the only Kotlin surface and we use config/v2; keep the
            // Kotlin stdlib off a Java-only compile classpath.
            exclude(group = "org.jetbrains.kotlin")
        }
        localRuntime("dev.isxander:yet-another-config-lib:$yaclVersion") {
            exclude(group = "org.jetbrains.kotlin")
        }
    }
    if (modmenuVersion != null) {
        compileOnly("com.terraformersmc:modmenu:$modmenuVersion")
        localRuntime("com.terraformersmc:modmenu:$modmenuVersion")
    }

    // JUnit lives in its own detached configuration -- see the bare-JVM test
    // wiring below.
    unitTest(platform("org.junit:junit-bom:${property("junit_version")}"))
    unitTest("org.junit.jupiter:junit-jupiter")
    unitTest("org.junit.platform:junit-platform-launcher")
    // JUnit's own API classes are annotated @API(status = Status.STABLE), but
    // JUnit's module metadata keeps apiguardian-api off the consumer compile
    // classpath. Without it javac reads those annotations, cannot find
    // org.apiguardian.api.API$Status, and emits ~100 "unknown enum constant
    // Status.STABLE" warnings that bury every real one. That diagnostic is
    // issued while reading class files and is NOT controlled by any -Xlint
    // category (verified: -Xlint:-classfile does not silence it), so the only
    // surgical fix is to put the missing annotation class on the classpath.
    unitTest("org.apiguardian:apiguardian-api:${property("apiguardian_version")}")
}

// --- Bare-JVM unit tests ------------------------------------------------------
// `gradlew test` must run on a plain JVM with NO Minecraft on the classpath, so
// tests stay fast and never need a booted game. Detaching testImplementation
// from implementation is not enough (Loom wires the merged Minecraft jar and its
// libraries straight onto testCompile/testRuntimeClasspath), but simply
// replacing the classpaths with "JUnit + our own output" is a trap: the mod's
// own classes then load without fabric-loader/slf4j/gson behind them and every
// test that touches SkyPrismClient dies with NoClassDefFoundError at run time
// while compiling perfectly.
//
// So: take the real runtime classpath and subtract *only* Minecraft itself --
// the merged jar, Mojang's libraries, LWJGL, Netty and the Mixin machinery.
// Everything a Minecraft-free class can legitimately need (fabric-loader,
// fabric-api, slf4j-api, gson, guava, ...) stays.
val minecraftGroups = setOf("net.minecraft", "com.mojang", "org.lwjgl", "io.netty")

// Belt and braces: artifacts Loom may contribute as file (non-module)
// dependencies, which a component filter cannot see.
val minecraftFilePrefixes = listOf("minecraft-merged", "sponge-mixin", "mixinextras")

val nonMinecraftRuntime: FileCollection = configurations.named("runtimeClasspath").get()
    .incoming.artifactView {
        componentFilter { id -> !(id is ModuleComponentIdentifier && id.group in minecraftGroups) }
    }.files
    .filter { f -> minecraftFilePrefixes.none { f.name.startsWith(it) } }

sourceSets.test {
    compileClasspath = unitTest + nonMinecraftRuntime + sourceSets.main.get().output
    runtimeClasspath = unitTest + nonMinecraftRuntime + output + sourceSets.main.get().output
}

// Tripwire: if Minecraft ever creeps back onto the unit-test classpath the test
// task fails immediately instead of silently going slow / needing a game.
val minecraftLeakMarkers = listOf(
    "minecraft-merged", "sponge-mixin", "mixinextras", "lwjgl", "netty-",
    "authlib", "brigadier", "datafixerupper", "blaze3d"
)

// SkyPrism is client-only, so splitEnvironmentSourceSets() is deliberately NOT
// used: everything lives in src/main/java and fabric.mod.json declares
// "environment": "client". Splitting only buys compile-time safety for a
// dual-sided mod, which this is not.

// Resolved outside the task blocks below: inside `tasks.processResources { }`
// the receiver is the Task, whose own property() would not see Gradle
// properties.
val modId: String = property("mod.id") as String
val modName: String = property("mod.name") as String

// The fabric.mod.json template placeholders, shared by processResources and the
// sources jar so neither can ship the raw ${id}/${version} template.
val modMetaProps: Map<String, String> = mapOf(
    "id" to modId,
    "name" to modName,
    "version" to project.version.toString(),
    "minecraft" to mcVersion
)

tasks.processResources {
    inputs.properties(modMetaProps)
    filesMatching("fabric.mod.json") { expand(modMetaProps) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    // TextClean and its test contain U+00A7; never let the platform default
    // encoding (windows-1252 on this machine) decide how sources are read.
    options.encoding = "UTF-8"
}

java {
    // Toolchain, not the JVM that happens to be running Gradle: compilation is
    // reproducible on any machine, and other machines can auto-provision JDK 25
    // via the foojay resolver applied in settings.gradle.kts.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

// withSourcesJar() copies src/main/resources verbatim, which would ship the raw
// templated fabric.mod.json (literal ${id}, ${version}, ...). Expand it with the
// same properties processResources uses.
tasks.named<Jar>("sourcesJar") {
    inputs.properties(modMetaProps)
    filesMatching("fabric.mod.json") { expand(modMetaProps) }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }

    doFirst {
        // Scan the Test task's OWN classpath property, not a configuration-time snapshot of
        // sourceSets.test.runtimeClasspath. The two agree today only because `test` never
        // assigns `classpath` and so rides the java plugin's live mapping; a Loom upgrade, an
        // IDE-integration plugin or any afterEvaluate block that reassigned it would put
        // Minecraft on the JVM the tests actually run on while the snapshot stayed clean --
        // i.e. exactly the silent failure this block exists to make loud. The mcTest tripwire
        // below never had this gap, because that task assigns its own classpath explicitly.
        val leaked = classpath.files.filter { f ->
            minecraftLeakMarkers.any { f.name.lowercase().startsWith(it) }
        }
        if (leaked.isNotEmpty()) {
            throw GradleException(
                "Minecraft leaked onto the unit-test classpath: " +
                    leaked.joinToString { it.name } +
                    ". Unit tests must run on a bare JVM -- see the sourceSets.test block."
            )
        }
    }
}

// --- Minecraft-aware tests (mcTest) -------------------------------------------
// A SECOND test source set, and deliberately the exact inverse of the one above:
// where `test` has Minecraft subtracted from its classpath, `mcTest` keeps the whole
// thing, so a test here may name Component, Style, ChatFormatting, PlayerInfo and
// every com.skyprism.mc.** adapter that wraps them.
//
// Why two source sets rather than one relaxed one: `gradlew test` is the suite that
// runs on every save, and its value is that it needs no game, no registries and about
// a second of wall clock. Letting one Minecraft-aware test into it would put the
// merged jar and its ~110 libraries on the classpath permanently, trip the leak
// detector above, and quietly make the fast suite the slow suite. So the split is the
// point, and NOTHING in this block touches sourceSets.test or its tripwire.
//
// `mcTest` is opt-in: it is wired to its own task and is NOT a dependency of `check`
// or `build`. Run it with `gradlew :26.2:mcTest` (or :26.1.2:mcTest).
//
// Everything here needs Minecraft *classes*, not a *booted* Minecraft: no test in this
// source set calls Bootstrap.bootStrap() or touches a registry, so the task is an
// ordinary headless JVM fork. If a future test ever does need registries, that is a
// much larger change -- SharedConstants.tryDetectVersion() plus Bootstrap.bootStrap()
// plus a writable run directory -- and it should be argued for explicitly rather than
// slipped into this block.
val mcTest: SourceSet = sourceSets.create("mcTest") {
    // Stonecutter points every node at the ONE shared source tree at the repository
    // root; a node's own projectDir (versions/<mc>/) holds only gradle.properties, a
    // run/ directory and build/. So the default $projectDir/src/mcTest/java would not
    // exist on either node and the source set would silently compile nothing. Name the
    // directories against rootProject, exactly as `tasks.jar` already does for LICENSE.
    java.setSrcDirs(listOf(rootProject.file("src/mcTest/java")))
    resources.setSrcDirs(listOf(rootProject.file("src/mcTest/resources")))
}

// Assigned rather than declared through mcTestImplementation, for the same reason the
// unit-test classpaths are assigned: Loom populates source-set classpaths itself, and
// an explicit assignment is the only form that cannot be quietly re-ordered by it.
// main's compileClasspath is the real one Loom built -- merged Minecraft jar, Mojang
// libraries, fabric-loader, fabric-api, plus the compileOnly YACL/ModMenu -- so this
// stays correct on both nodes without naming a single artifact.
mcTest.compileClasspath = unitTest + sourceSets.main.get().compileClasspath + sourceSets.main.get().output
mcTest.runtimeClasspath = unitTest + mcTest.output + sourceSets.main.get().output +
    configurations.named("runtimeClasspath").get()

tasks.register<Test>("mcTest") {
    group = "verification"
    description = "Runs the Minecraft-aware behavioural tests (src/mcTest). Not part of `check`."
    testClassesDirs = mcTest.output.classesDirs
    classpath = mcTest.runtimeClasspath
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
    // Not a dependency of check, but if someone asks for both in one invocation the
    // fast suite should report first.
    shouldRunAfter(tasks.test)

    // The mirror image of the unit-test tripwire: there, Minecraft appearing is the
    // failure; here, Minecraft *disappearing* is. Without this a Loom change that
    // stopped putting the merged jar on runtimeClasspath would surface as a pile of
    // NoClassDefFoundErrors inside individual tests rather than as one clear message.
    val mcTestRuntime = mcTest.runtimeClasspath
    doFirst {
        val hasMinecraft = mcTestRuntime.files.any { it.name.startsWith("minecraft-merged") }
        if (!hasMinecraft) {
            throw GradleException(
                "Minecraft is missing from the mcTest runtime classpath. This source set " +
                    "exists precisely to have it -- see the mcTest block in build.gradle.kts."
            )
        }
    }
}

tasks.jar {
    from(rootProject.file("LICENSE")) {
        rename { "${it}_$modId" }
    }
}

// Collects each node's jar into <root>/build/libs/<mc version>/ so both
// versions' artifacts sit side by side after `gradlew buildAllVersions`.
val buildAndCollect by tasks.registering(Copy::class) {
    group = "build"
    description = "Copies this node's jar into the root build/libs/$mcVersion directory."
    from(tasks.jar.map { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs/$mcVersion"))
    dependsOn(tasks.jar)
}

tasks.build { finalizedBy(buildAndCollect) }

// Diagnostic: prove the unit-test classpath carries no Minecraft.
tasks.register("printTestClasspath") {
    group = "help"
    description = "Prints the unit-test runtime classpath, one entry per line."
    val cp = sourceSets.test.get().runtimeClasspath
    doLast { cp.forEach { println(it) } }
}

// Diagnostic twin of printTestClasspath, for the Minecraft-aware source set.
tasks.register("printMcTestClasspath") {
    group = "help"
    description = "Prints the mcTest runtime classpath, one entry per line."
    val cp = sourceSets.named("mcTest").get().runtimeClasspath
    doLast { cp.forEach { println(it) } }
}

// Prints the main compile classpath so agents/tools can javac-check sources without booting Gradle
// for every edit. Diagnostic only - nothing in the build depends on it.
tasks.register("printCompileClasspath") {
    group = "help"
    description = "Prints the main compileClasspath, one absolute path per line."
    val cp = sourceSets.main.get().compileClasspath
    doLast { cp.forEach { println(it.absolutePath) } }
}

// --- Dev-client system properties ---------------------------------------------
// `gradlew :26.2:runClient -Dskyprism.selftest=true` sets the property on the
// GRADLE daemon's JVM. Loom's run tasks are forked JavaExecs, so without this
// block the property never reaches the game and SelfTest.arm() is simply never
// called -- the client boots to the title screen, sits there, and the run looks
// like a hang rather than a misconfiguration. Forward the mod's own namespace
// (and nothing else) into every run config so the command documented in
// docs/TESTING.md does what it says.
loom {
    runs.configureEach {
        System.getProperties().stringPropertyNames()
            .filter { it == "skyprism" || it.startsWith("skyprism.") }
            .forEach { name -> property(name, System.getProperty(name)) }
    }
}

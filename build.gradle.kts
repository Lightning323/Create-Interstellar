import java.time.*

plugins {
    `maven-publish`
    id("net.neoforged.moddev") version "2.0.141"
    id("net.kyori.blossom") version "2.2.0"
    kotlin("jvm") version "2.1.21"
}

version = "0.6.6+1.21.1"
group = "com.lightning.northstar"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

val generatedResources = file("src/generated")

sourceSets {
    main {
        // The data generator output must be part of the main resources: in dev runs FML builds
        // the mod's data pack ("mod/northstar") from the main source set output, so biomes,
        // dimension types, etc. have to be staged into build/resources/main via processResources.
        // (This does NOT create a task cycle: declaring an input directory does not make
        // processResources depend on the data run. Only an explicit dependsOn would do that.)
        resources.srcDir(generatedResources)

        blossom.javaSources {
            property("version", version.toString())
        }
    }

    val main by getting

    val tfmgCe = create("tfmg-ce") {
        compileClasspath += (main.output + main.compileClasspath).filter { "tfmg" !in it.name }
    }

    tasks.named<Jar>("jar") {
        from(tfmgCe.output)
    }

    tasks.named<Jar>("sourcesJar") {
        from(tfmgCe.allSource)
    }

    tasks.named<Javadoc>("javadoc") {
        source(tfmgCe.allJava)
        classpath += tfmgCe.compileClasspath + tfmgCe.output
    }
}

// ModDevGradle turns the default `test` task into a NeoForge game launch, so plain
// JUnit tests need their own JVM suite. `unitTest` runs them on the normal test
// runtime without booting Minecraft.
testing {
    suites {
        val unitTest by registering(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit.get())
            // ModDevGradle only wires Minecraft and the mod dependencies onto the
            // main source set, so unit tests that touch mod types (Direction,
            // Vec3, JOML, SmartBlockEntity, ...) need the main classpath and
            // output added explicitly.
            dependencies {
                implementation(sourceSets.main.get().output)
                implementation(sourceSets.main.get().compileClasspath)
                runtimeOnly(sourceSets.main.get().runtimeClasspath)
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.named("test"))
                    testLogging {
                        events("passed", "skipped", "failed")
                    }
                }
            }
        }
    }
}

neoForge {
    version = "21.1.230"

    parchment {
        minecraftVersion = "1.21.1"
        mappingsVersion = "2024.11.17"
    }

    validateAccessTransformers = true
    interfaceInjectionData.from("interfaces.json")

    runs {
        configureEach {
            systemProperty("geckolib.disable_examples", "true")
            systemProperty("mixin.debug.export", "true")
            //systemProperty("forge.logging.markers", "REGISTRIES,REGISTRYDUMP")
            //systemProperty("forge.logging.console.level", "debug")
        }

        create("client") {
            client()
            gameDirectory = file("run")
            jvmArgument("-Xmx4G")
        }
        create("data") {
            data()
            gameDirectory = file("run")
            jvmArgument("-Xmx4G")
            programArguments.addAll(
                "--all",
                "--mod", "northstar",
                "--output", generatedResources.absolutePath,
                "--existing", file("src/main/resources").absolutePath
            )
        }
        create("server") {
            server()
            gameDirectory = file("run-server")
            jvmArgument("-Xmx4G")
        }
    }

    mods {
        create("northstar") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets["tfmg-ce"])
        }
    }
}

repositories {
    mavenCentral()
    maven("https://modmaven.dev/")
    maven("https://maven.tterrag.com/")
    maven("https://maven.createmod.net")
    maven("https://maven.architectury.dev/")
    maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven/") // Ponder
    maven("https://maven.blamejared.com/") // JEI
    maven("https://maven.ithundxr.dev/snapshots") // Registrate
    maven("https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/") { // GeckoLib
        content {
            includeGroupByRegex("software\\.bernie.*")
            includeGroup("com.eliotlash.mclib")
        }
    }
    maven("https://maven.ftb.dev/releases")
    maven("https://cursemaven.com") {
        content {
            includeGroup("curse.maven")
        }
    }
    maven("https://api.modrinth.com/maven") {
        content {
            includeGroup("maven.modrinth")
        }
    }
    maven("https://maven.parchmentmc.org/") {
        content {
            includeGroupByRegex("org\\.parchmentmc.*")
        }
    }
    maven("https://maven.latvian.dev/releases") {
        content {
            includeGroupByRegex("dev\\.latvian\\..*")
        }
    }
    maven("https://maven.ryanhcode.dev/releases") {
        content {
            includeGroup("dev.ryanhcode.sable")
            includeGroup("dev.ryanhcode.sable-companion")
        }
    }
    flatDir { dir("run/mods-obf-1.21.1") }
}

dependencies {
    //annotationProcessor(variantOf(libs.mixin) { classifier("processor") })
    compileOnly(libs.mixin)
    //annotationProcessor(libs.mixinextras.common)
    implementation(libs.mixinextras.common)
    implementation(libs.mixinextras.neoforge)
    jarJar(libs.mixinextras.neoforge)

    implementation(variantOf(libs.create) { classifier("slim") }) { isTransitive = false }
    implementation(libs.ponder.neoforge)
    implementation(libs.registrate)
    compileOnly(libs.flywheel.neoforge.api)
    runtimeOnly(libs.flywheel.neoforge)

    implementation(libs.geckolib.neoforge)

    compileOnly(libs.iris)

    implementation(libs.jei.neoforge)
    implementation(libs.copycats)
    implementation(libs.cdg)
    implementation(libs.cca)
    implementation(libs.kubejs) { isTransitive = false }
    implementation(libs.kubejs.create)
    implementation(libs.rhino)
    implementation(libs.sable)
    implementation(libs.sable.companion)
    implementation(libs.tfmg)
    implementation(libs.aeronautics)
    implementation(libs.create.kinetic)
    implementation(libs.creative.mode.tweaks)

    "tfmgCeImplementation"(libs.tfmg.ce)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Create a folder name "mods-obf" inside "run" and put extra mods needed for testing here
    file("run/mods-obf-1.21.1").listFiles()?.forEach { runtimeOnly("local:${it.nameWithoutExtension}") }
}

// The worldgen datapack (biomes, placed/configured features, dimension types, ...) only exists as
// data generator output in $generatedResources, which is not checked in. Anything that packages or
// loads the mod's resources has to generate it first, otherwise data/northstar/dimension/*.json
// references biomes that are not in the registry and the registries fail to load
// ("No key preset in MapLike[...]" / "Failed to get element ResourceKey[minecraft:worldgen/biome ...]").
//
// Dev runs load the mod's data pack from the main source set output, which already includes the
// generator output via `resources.srcDir` above, so no extra wiring is needed there. For packaging,
// processResources may run before the data generator on a fresh checkout, so the jar additionally
// bundles a post-data-run copy of the generated resources to guarantee it is never stale.
val generatedOutput = layout.buildDirectory.dir("generated/resources/main")

val generateResources = tasks.register<Sync>("generateResources") {
    group = "northstar"
    description = "Runs the data generator and stages its output for the jar."
    dependsOn(tasks.named("runData"))
    from(generatedResources)
    into(generatedOutput)
}

tasks.named<Jar>("jar") {
    from(generateResources)
    // main resources already contain src/generated via resources.srcDir above, so
    // the staged data-run copy overlaps it. Both hold the same content, so take
    // the first rather than failing the jar on a duplicate entry.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.jar {
    manifest {
        attributes(mapOf(
            "Specification-Title" to "northstar",
            "Specification-Vendor" to "Redstonneur1256",
            "Specification-Version" to version,
            "Implementation-Title" to project.name,
            "Implementation-Version" to version,
            "Implementation-Vendor" to "Redstonneur1256",
            "Implementation-Timestamp" to Instant.now().toString(),
            "MixinConfigs" to "northstar.mixins.json"
        ))
    }
}

tasks.processResources {
    val buildProps = project.properties.toMutableMap()
    buildProps["file"] = mapOf("jarVersion" to project.version)
    filesMatching(listOf("META-INF/neoforge.mods.toml")) {
        expand(buildProps)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "10000"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            repositories {
                maven {
                    name = "SkyPlex"
                    credentials(PasswordCredentials::class.java)
                    url = uri("https://repo.mc-skyplex.net/releases/")
                }
            }
        }
    }
}

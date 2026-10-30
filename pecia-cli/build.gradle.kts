import java.security.MessageDigest

plugins {
    id("pecia.java-conventions")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":pecia-core"))
    implementation(libs.picocli)
    annotationProcessor(libs.picocli.codegen)
    // Silences JGit's SLF4J "no logger found" warning on stderr; we do not log.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(testFixtures(project(":pecia-core")))
    // Enforced, so the BOM's JUnit Platform version wins over jqwik's older requests instead of counting as a conflict.
    testImplementation(enforcedPlatform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.gson)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.compileJava {
    options.compilerArgs.addAll(
        listOf(
            // picocli-codegen inspects annotations without claiming them, which javac reports.
            "-Xlint:-processing",
            "-Aproject=${project.group}/${project.name}",
        )
    )
}

tasks.shadowJar {
    archiveFileName = "pecia.jar"
    manifest {
        attributes("Main-Class" to "dev.eyuppastirmaci.pecia.Bootstrap", "Implementation-Version" to project.version)
    }
    // Drop dependency signature files; they no longer match once everything is merged into one JAR.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    // ONNX Runtime ships macOS debug symbols beside its libraries; they add about 57 MB and are never loaded.
    exclude("ai/onnxruntime/native/**/*.dSYM/**")
    // Keep the JAR single-release, as it has always been, so the JVM ignores dependencies' META-INF/versions entries.
    addMultiReleaseAttribute = false
}

// Real-model checks need the model in the cache that PECIA_CACHE_DIR names, so only referenceTest runs them.
val referenceTag = "reference"
val cliJar = tasks.shadowJar.flatMap { it.archiveFile }

tasks.named<Test>("integrationTest") {
    inputs.file(cliJar).withPropertyName("cliJar")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dpecia.it.jar=${cliJar.get().asFile.absolutePath}", "-Dpecia.it.version=${project.version}")
    })
    useJUnitPlatform {
        excludeTags(referenceTag)
    }
}

tasks.register<Test>("referenceTest") {
    description = "Runs the packaged CLI with the real semantic model copied from the cache that PECIA_CACHE_DIR names."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*IT.class")
    inputs.file(cliJar).withPropertyName("cliJar")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dpecia.it.jar=${cliJar.get().asFile.absolutePath}") })
    useJUnitPlatform {
        includeTags(referenceTag)
    }
    shouldRunAfter(tasks.named("integrationTest"))
    // The model lives outside the build, so Gradle cannot tell when a previous result is still valid: never reuse one,
    // from this build or from the build cache.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("The model lives outside the build") { true }
}

// Platform packages carry a JAR trimmed to one platform's native libraries and a jlink runtime, so users need no Java.
// Each package is built on its own platform, because jlink cannot build for another one.
val supportedPlatforms = listOf("osx-aarch64", "linux-x64", "linux-aarch64", "win-x64")
val packagePlatform = providers.gradleProperty("pecia.platform").orElse(providers.provider { hostPlatform() })

fun hostPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val system = when {
        os.startsWith("mac") -> "osx"
        os.startsWith("windows") -> "win"
        os.startsWith("linux") -> "linux"
        else -> os
    }
    val cpu = when (arch) {
        "aarch64", "arm64" -> "aarch64"
        "amd64", "x86_64" -> "x64"
        else -> arch
    }
    return "$system-$cpu"
}

/** The directories of native libraries a package for the platform keeps; every other platform's are dropped. */
fun nativeDirectories(platform: String): List<String> {
    require(platform in supportedPlatforms) { "No package for $platform; supported: $supportedPlatforms" }
    val (system, cpu) = platform.split("-")
    val sqliteCpu = if (cpu == "x64") "x86_64" else cpu
    val sqliteSystems = when (system) {
        "osx" -> listOf("Mac")
        "win" -> listOf("Windows")
        // musl builds keep lexical search working on distributions such as Alpine.
        else -> listOf("Linux", "Linux-Musl")
    }
    return listOf("ai/onnxruntime/native/$platform/", "dev/eyuppastirmaci/pecia/semantic/sqlite-vec/$platform/") +
        sqliteSystems.map { "org/sqlite/native/$it/$sqliteCpu/" }
}

val nativeRoots = listOf("ai/onnxruntime/native/", "dev/eyuppastirmaci/pecia/semantic/sqlite-vec/", "org/sqlite/native/")

val platformJar = tasks.register<Zip>("platformJar") {
    description = "Builds the CLI JAR with only the native libraries of one platform (-Ppecia.platform, default: this one)."
    group = "distribution"
    val platform = packagePlatform
    inputs.property("platform", platform)
    from(zipTree(tasks.shadowJar.flatMap { it.archiveFile })) {
        exclude { entry ->
            val path = entry.path + if (entry.isDirectory) "/" else ""
            val kept = nativeDirectories(platform.get())
            nativeRoots.any { path.startsWith(it) } && kept.none { path.startsWith(it) || it.startsWith(path) }
        }
    }
    archiveFileName = "pecia.jar"
    destinationDirectory = layout.buildDirectory.dir("package/jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// jdeps reports these modules for the CLI JAR; jdk.crypto.ec adds the elliptic-curve TLS that model downloads need,
// which jdeps cannot see and which JDK 21 keeps outside java.base.
val runtimeModules = listOf("java.base", "java.management", "java.net.http", "java.security.jgss", "java.sql", "jdk.crypto.ec")

val runtimeImage = tasks.register<Exec>("runtimeImage") {
    description = "Builds a minimal Java runtime for the CLI with jlink from the JDK 21 toolchain."
    group = "distribution"
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    val output = layout.buildDirectory.dir("package/runtime")
    inputs.property("modules", runtimeModules)
    outputs.dir(output)
    doFirst {
        delete(output)
        val suffix = if (System.getProperty("os.name").lowercase().startsWith("windows")) ".exe" else ""
        executable = jdk.get().metadata.installationPath.file("bin/jlink$suffix").asFile.absolutePath
    }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--add-modules", runtimeModules.joinToString(","),
            "--strip-debug", "--no-man-pages", "--no-header-files", "--compress=zip-6",
            "--output", output.get().asFile.absolutePath,
        )
    })
}

val packageVersion = project.version.toString()
val isWindowsPackage = packagePlatform.get().startsWith("win")
val packageName = "pecia-$packageVersion-${packagePlatform.get()}"

// Third-party licences travel inside the JAR; packages also show them beside it.
val packageLicenses = tasks.register<Sync>("packageLicenses") {
    from(zipTree(platformJar.flatMap { it.archiveFile })) {
        include(
            "META-INF/licenses/**",
            "META-INF/maven/org.xerial/sqlite-jdbc/LICENSE",
            "dev/eyuppastirmaci/pecia/semantic/sqlite-vec/NOTICE.txt",
            "dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/LICENSE.txt",
            "dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/NOTICE.txt",
        )
        eachFile {
            name = when {
                path.startsWith("META-INF/licenses/") -> name
                path.contains("sqlite-jdbc") -> "sqlite-jdbc-LICENSE.txt"
                path.contains("sqlite-vec") -> "sqlite-vec-NOTICE.txt"
                else -> "all-MiniLM-L6-v2-$name"
            }
            path = name
        }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("package/licenses"))
}

// On Windows, jpackage's launcher reads the wide command line, so arguments outside the ANSI code page survive.
val windowsAppImage = tasks.register<Exec>("windowsAppImage") {
    description = "Builds the Windows launcher and application layout with jpackage."
    group = "distribution"
    enabled = isWindowsPackage
    dependsOn(platformJar, runtimeImage)
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    val output = layout.buildDirectory.dir("package/jpackage")
    outputs.dir(output)
    doFirst {
        delete(output)
        executable = jdk.get().metadata.installationPath.file("bin/jpackage.exe").asFile.absolutePath
    }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--type", "app-image", "--name", "pecia", "--win-console",
            // jpackage accepts only numeric versions, so a pre-release suffix stays in the JAR and the archive name.
            "--app-version", packageVersion.substringBefore("-"),
            "--input", platformJar.get().destinationDirectory.get().asFile.absolutePath,
            "--main-jar", "pecia.jar",
            "--main-class", "dev.eyuppastirmaci.pecia.Bootstrap",
            "--runtime-image", layout.buildDirectory.dir("package/runtime").get().asFile.absolutePath,
            "--java-options", "-Dpecia.command=pecia",
            "--dest", output.get().asFile.absolutePath,
        )
    })
}

val packageImage = tasks.register<Sync>("packageImage") {
    description = "Lays out the package for this platform: launcher, Java runtime, JAR, and licences."
    group = "distribution"
    into(layout.buildDirectory.dir("package/image/$packageName"))
    // jlink writes its legal notices read-only, so a second sync could not overwrite them: start from nothing.
    doFirst { delete(layout.buildDirectory.dir("package/image")) }
    from(rootProject.file("LICENSE"))
    from(packageLicenses) { into("licenses") }

    if (isWindowsPackage) {
        from(windowsAppImage.map { layout.buildDirectory.dir("package/jpackage/pecia") })
    } else {
        from(platformJar) { into("lib") }
        from(runtimeImage) { into("runtime") }
        // The launcher's source folder is not called bin, which .gitignore reserves for IDE build output.
        from("src/package/launcher") {
            into("bin")
            filePermissions { unix("rwxr-xr-x") }
        }
    }
}

val packageArchive = if (isWindowsPackage) {
    tasks.register<Zip>("packageArchive") { from(packageImage.map { it.destinationDir.parentFile }) }
} else {
    tasks.register<Tar>("packageArchive") {
        from(packageImage.map { it.destinationDir.parentFile })
        compression = Compression.GZIP
        archiveExtension = "tar.gz"
    }
}

packageArchive.configure {
    description = "Archives the package for this platform and writes its SHA-256 beside it."
    group = "distribution"
    archiveFileName = packageName + if (isWindowsPackage) ".zip" else ".tar.gz"
    destinationDirectory = layout.buildDirectory.dir("package/dist")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    // Gradle normalises permissions by default; the launcher, java, and jspawnhelper must stay executable.
    useFileSystemPermissions()
    doLast {
        val archive = archiveFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
        archive.resolveSibling(archive.name + ".sha256")
            .writeText(digest.joinToString("") { "%02x".format(it) } + "  " + archive.name + "\n")
    }
}

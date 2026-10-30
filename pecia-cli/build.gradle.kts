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
        attributes("Main-Class" to "dev.eyuppastirmaci.pecia.Bootstrap")
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
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dpecia.it.jar=${cliJar.get().asFile.absolutePath}") })
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

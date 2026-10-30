import java.nio.file.Path

plugins {
    id("pecia.java-conventions")
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.pitest)
}

dependencies {
    implementation(libs.sqlite.jdbc)
    implementation(libs.commonmark)
    // Used only for its .gitignore rule engine (org.eclipse.jgit.ignore).
    implementation(libs.jgit)
    implementation(libs.tomlj)
    // Loaded only when an embedder is opened, so lexical runs never initialize it.
    implementation(libs.onnxruntime)

    // Enforced, so the BOM's JUnit Platform version wins over jqwik's older requests instead of counting as a conflict.
    testImplementation(enforcedPlatform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Reference checks need the real model in the cache that PECIA_CACHE_DIR names, so only referenceTest runs them.
val referenceTag = "reference"
// Performance measurements take minutes and assert nothing about time, so only performanceTest runs them.
val performanceTag = "performance"

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags(referenceTag, performanceTag)
    }
    // LibraryGuideTest compares the guide's example with its own source, whose comments and spacing leave the compiled
    // classes unchanged, so both files are inputs.
    inputs.file(layout.projectDirectory.file("../docs/library.md")).withPropertyName("libraryGuide")
    inputs.file("src/test/java/dev/eyuppastirmaci/pecia/LibraryGuideTest.java").withPropertyName("libraryGuideTest")
}

tasks.register<Test>("referenceTest") {
    description = "Compares the real model's embeddings with the committed sentence-transformers references."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags(referenceTag)
    }
    shouldRunAfter(tasks.test)
    // The model lives outside the build, so Gradle cannot tell when a previous result is still valid: never reuse one,
    // from this build or from the build cache.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("The model lives outside the build") { true }
}

tasks.register<Test>("performanceTest") {
    description = "Measures indexing and query performance on a synthetic corpus and writes a report; never a gate."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags(performanceTag)
    }
    // One JVM, so no other test competes for the processors being measured.
    maxParallelForks = 1
    // -Ppecia.performance.files=N and -Ppecia.performance.semanticFiles=N change the corpus sizes.
    listOf("pecia.performance.files", "pecia.performance.semanticFiles").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    systemProperty("junit.jupiter.execution.timeout.default", "30 m")
    val reports = layout.buildDirectory.dir("reports/performance")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dpecia.performance.reports=${reports.get().asFile.absolutePath}")
    })
    // Timings change from run to run, so a previous result is never reused.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Timings change from run to run") { true }
}

// The retrieval-quality checks write a report per search mode beside Gradle's own test reports.
val retrievalReports = layout.buildDirectory.dir("reports/retrieval")
listOf("test", "referenceTest").forEach { name ->
    tasks.named<Test>(name) {
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Dpecia.retrieval.reports=${retrievalReports.get().asFile.absolutePath}")
        })
    }
}

// The packaged-core consumer runs on the core JAR plus exactly these runtime dependencies.
val itRuntime = tasks.register<Sync>("itRuntime") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("it-runtime"))
}

tasks.named<Test>("integrationTest") {
    val coreJar = tasks.jar.flatMap { it.archiveFile }
    val testClasses = sourceSets.test.flatMap { it.java.classesDirectory }
    inputs.file(coreJar).withPropertyName("coreJar")
    // Declaring the sync task as an input also makes this task depend on it.
    inputs.files(itRuntime).withPropertyName("runtimeDirectory")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dpecia.it.jar=${coreJar.get().asFile.absolutePath}",
            "-Dpecia.it.runtimeDirectory=${itRuntime.get().destinationDir.absolutePath}",
            "-Dpecia.it.testClasses=${testClasses.get().asFile.absolutePath}",
        )
    })
}

// Mutation testing of the code whose mistakes tests miss most easily: chunking, incremental indexing decisions, and
// ranking. It runs only on demand (./gradlew :pecia-core:pitest), never in check, and writes build/reports/pitest.
pitest {
    pitestVersion = libs.versions.pitest.asProvider().get()
    junit5PluginVersion = libs.versions.pitest.junit5.plugin.get()
    targetClasses = setOf(
        "dev.eyuppastirmaci.pecia.chunking.*",
        "dev.eyuppastirmaci.pecia.index.FileInspector*",
        "dev.eyuppastirmaci.pecia.index.IndexDecision*",
        "dev.eyuppastirmaci.pecia.index.IndexDeletionPlanner*",
        "dev.eyuppastirmaci.pecia.search.RankFusion*",
        "dev.eyuppastirmaci.pecia.search.LexicalQueryCompiler*",
        "dev.eyuppastirmaci.pecia.search.QueryShape*",
    )
    // Every unit test may cover these classes, not only tests named after them; packaged tests run the JAR, where
    // mutations do not reach.
    targetTests = setOf("dev.eyuppastirmaci.pecia.*")
    excludedTestClasses = setOf("*IT", "dev.eyuppastirmaci.pecia.packaging.*")
    excludedGroups = setOf(referenceTag, performanceTag)
    threads = 4
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
}

tasks.named<info.solidsoft.gradle.pitest.PitestTask>("pitest") {
    // Like the test tasks: never the user's cache, and a temporary directory without symbolic links.
    environment("PECIA_CACHE_DIR", layout.buildDirectory.dir("pitest-cache").get().asFile.absolutePath)
    jvmArgs = listOf("-Djava.io.tmpdir=" + Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
}


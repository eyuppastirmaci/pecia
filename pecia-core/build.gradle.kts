plugins {
    id("pecia.java-conventions")
    `java-library`
    `java-test-fixtures`
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

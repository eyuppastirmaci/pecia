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
